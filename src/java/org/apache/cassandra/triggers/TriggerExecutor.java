/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cassandra.triggers;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;

import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.exceptions.CassandraException;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.schema.TableId;
import org.apache.cassandra.schema.TriggerMetadata;
import org.apache.cassandra.schema.Triggers;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;

public class TriggerExecutor
{
    public static final TriggerExecutor instance = new TriggerExecutor();

    private final Map<String, ITrigger> cachedTriggers = Maps.newConcurrentMap();
    private final ClassLoader parent = Thread.currentThread().getContextClassLoader();
    private volatile ClassLoader customClassLoader;

    private TriggerExecutor()
    {
        reloadClasses();
    }

    /**
     * Reload the triggers which is already loaded, Invoking this will update
     * the class loader so new jars can be loaded.
     */
    public void reloadClasses()
    {
        File triggerDirectory = FBUtilities.cassandraTriggerDir();
        if (triggerDirectory == null)
            return;
        customClassLoader = new CustomClassLoader(parent, triggerDirectory);
        cachedTriggers.clear();
    }

    /**
     * Augment a partition update by executing triggers to generate an intermediate
     * set of mutations, then merging the update from each mutation with those
     * supplied. This is called from @{link org.apache.cassandra.service.StorageProxy#cas}
     * which is scoped for a single partition. For that reason, any mutations generated
     * by triggers are checked to ensure that they are for the same table and partition
     * key as the primary update; if not, InvalidRequestException is thrown. If no
     * additional mutations are generated, the original updates are returned unmodified.
     *
     * @param updates partition update to be applied, contains the merge of the original
     *                update and any generated mutations
     * @return the final update to be applied, the original update merged with any
     * additional  mutations generated by configured triggers
     * @throws InvalidRequestException if any mutation generated by a trigger does not
     * apply to the exact same partition as the initial update
     */
    public PartitionUpdate execute(PartitionUpdate updates) throws InvalidRequestException
    {
        List<Mutation> intermediate = executeInternal(updates);
        if (intermediate == null || intermediate.isEmpty())
            return updates;

        List<PartitionUpdate> augmented = validateForSinglePartition(updates.metadata().id,
                                                                     updates.partitionKey(),
                                                                     intermediate);
        // concatenate augmented and origin
        augmented.add(updates);
        return PartitionUpdate.merge(augmented);
    }

    /**
     * Takes a collection of mutations and possibly augments it by adding extra mutations
     * generated by configured triggers. If no additional mutations are created
     * this returns null, signalling to the caller that only the initial set of
     * mutations should be applied. If additional mutations <i>are</i> generated,
     * the total set (i.e. the original plus the additional mutations) are applied
     * together in a logged batch. Should this not be possible because the initial
     * mutations contain counter updates, InvalidRequestException is thrown.
     *
     * @param mutations initial collection of mutations
     * @return augmented mutations. Either the union of the initial and additional
     * mutations or null if no additional mutations were generated
     * @throws InvalidRequestException if additional mutations were generated, but
     * the initial mutations contains counter updates
     */
    public Collection<Mutation> execute(Collection<? extends IMutation> mutations) throws InvalidRequestException
    {
        boolean hasCounters = false;
        List<Mutation> augmentedMutations = null;

        for (IMutation mutation : mutations)
        {
            if (mutation instanceof CounterMutation)
                hasCounters = true;

            for (PartitionUpdate upd : mutation.getPartitionUpdates())
            {
                List<Mutation> augmentations = executeInternal(upd);
                if (augmentations == null || augmentations.isEmpty())
                    continue;

                validate(augmentations);

                if (augmentedMutations == null)
                    augmentedMutations = new LinkedList<>();
                augmentedMutations.addAll(augmentations);
            }
        }

        if (augmentedMutations == null)
            return null;

        if (hasCounters)
            throw new InvalidRequestException("Counter mutations and trigger mutations cannot be applied together atomically.");

        @SuppressWarnings("unchecked")
        Collection<Mutation> originalMutations = (Collection<Mutation>) mutations;

        return mergeMutations(Iterables.concat(originalMutations, augmentedMutations));
    }

    private Collection<Mutation> mergeMutations(Iterable<Mutation> mutations)
    {
        ListMultimap<Pair<String, ByteBuffer>, Mutation> groupedMutations = ArrayListMultimap.create();

        for (Mutation mutation : mutations)
        {
            Pair<String, ByteBuffer> key = Pair.create(mutation.getKeyspaceName(), mutation.key().getKey());
            groupedMutations.put(key, mutation);
        }

        List<Mutation> merged = new ArrayList<>(groupedMutations.size());
        for (Pair<String, ByteBuffer> key : groupedMutations.keySet())
            merged.add(Mutation.merge(groupedMutations.get(key)));

        return merged;
    }

    private List<PartitionUpdate> validateForSinglePartition(TableId tableId,
                                                             DecoratedKey key,
                                                             Collection<Mutation> tmutations)
    throws InvalidRequestException
    {
        validate(tmutations);

        if (tmutations.size() == 1)
        {
            List<PartitionUpdate> updates = Lists.newArrayList(Iterables.getOnlyElement(tmutations).getPartitionUpdates());
            if (updates.size() > 1)
                throw new InvalidRequestException("The updates generated by triggers are not all for the same partition");
            validateSamePartition(tableId, key, Iterables.getOnlyElement(updates));
            return updates;
        }

        ArrayList<PartitionUpdate> updates = new ArrayList<>(tmutations.size());
        for (Mutation mutation : tmutations)
        {
            for (PartitionUpdate update : mutation.getPartitionUpdates())
            {
                validateSamePartition(tableId, key, update);
                updates.add(update);
            }
        }
        return updates;
    }

    private void validateSamePartition(TableId tableId, DecoratedKey key, PartitionUpdate update)
    throws InvalidRequestException
    {
        if (!key.equals(update.partitionKey()))
            throw new InvalidRequestException("Partition key of additional mutation does not match primary update key");

        if (!tableId.equals(update.metadata().id))
            throw new InvalidRequestException("table of additional mutation does not match primary update table");
    }

    private void validate(Collection<Mutation> tmutations) throws InvalidRequestException
    {
        for (Mutation mutation : tmutations)
        {
            QueryProcessor.validateKey(mutation.key().getKey());
            for (PartitionUpdate update : mutation.getPartitionUpdates())
                update.validate();
        }
    }

    /**
     * Switch class loader before using the triggers for the column family, if
     * not loaded them with the custom class loader.
     */
    private List<Mutation> executeInternal(PartitionUpdate update)
    {
        Triggers triggers = update.metadata().triggers;
        if (triggers.isEmpty())
            return null;
        List<Mutation> tmutations = Lists.newLinkedList();
        Thread.currentThread().setContextClassLoader(customClassLoader);
        try
        {
            for (TriggerMetadata td : triggers)
            {
                ITrigger trigger = cachedTriggers.get(td.classOption);
                if (trigger == null)
                {
                    trigger = loadTriggerInstance(td.classOption);
                    cachedTriggers.put(td.classOption, trigger);
                }
                Collection<Mutation> temp = trigger.augment(update);
                if (temp != null)
                    tmutations.addAll(temp);
            }
            return tmutations;
        }
        catch (CassandraException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new RuntimeException(String.format("Exception while executing trigger on table with ID: %s", update.metadata().id), ex);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(parent);
        }
    }

    public synchronized ITrigger loadTriggerInstance(String triggerClass) throws Exception
    {
        // double check.
        if (cachedTriggers.get(triggerClass) != null)
            return cachedTriggers.get(triggerClass);
        return (ITrigger) customClassLoader.loadClass(triggerClass).getConstructor().newInstance();
    }
}
