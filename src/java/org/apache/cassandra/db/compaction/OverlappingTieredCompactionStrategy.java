package org.apache.cassandra.db.compaction;

import java.util.*;
import java.util.Map.Entry;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.utils.Pair;

public class OverlappingTieredCompactionStrategy extends AbstractCompactionStrategy
{
    protected OverlappingTieredCompactionStrategyOptions options;
    protected volatile int estimatedRemainingTasks;
    
    public OverlappingTieredCompactionStrategy(ColumnFamilyStore cfs,Map<String, String> options) {
        super(cfs, options);
    }

    private static final Logger logger = LoggerFactory.getLogger(OverlappingTieredCompactionStrategy.class);
    
    private static final Comparator<Pair<List<SSTableReader>,Double>> bucketsByHotnessComparator = new Comparator<Pair<List<SSTableReader>, Double>>()
    {
        public int compare(Pair<List<SSTableReader>, Double> o1, Pair<List<SSTableReader>, Double> o2)
        {
            int comparison = Double.compare(o1.right, o2.right);
            if (comparison != 0)
                return comparison;
            return Long.compare(avgSize(o1.left), avgSize(o2.left));
        }

        private long avgSize(List<SSTableReader> sstables)
        {
            long n = 0;
            for (SSTableReader sstable : sstables)
                n += sstable.bytesOnDisk();
            return n / sstables.size();
        }
    };

    public static <T> List<List<T>> getBuckets(Collection<Pair<T, Long>> files)
    {
        List<Pair<T, Long>> sortedFiles = new ArrayList<Pair<T, Long>>(files);
        Collections.sort(sortedFiles, new Comparator<Pair<T, Long>>()
        {
            public int compare(Pair<T, Long> p1, Pair<T, Long> p2)
            {
                return p1.right.compareTo(p2.right);
            }
        });

        Map<Long, List<T>> buckets = new HashMap<Long, List<T>>();
        outer:
        for (Pair<T, Long> pair: sortedFiles)
        {
            long size = pair.right;

            for (Entry<Long, List<T>> entry : buckets.entrySet())
            {
                List<T> bucket = entry.getValue();
                long oldAverageSize = entry.getKey();
                buckets.remove(oldAverageSize);
                long totalSize = bucket.size() * oldAverageSize;
                long newAverageSize = (totalSize + size) / (bucket.size() + 1);
                bucket.add(pair.left);
                buckets.put(newAverageSize, bucket);
                continue outer;
            }

            // no similar bucket found; put it in a new one
            ArrayList<T> bucket = new ArrayList<T>();
            bucket.add(pair.left);
            buckets.put(size, bucket);
        }

        return new ArrayList<List<T>>(buckets.values());

}

    private static double hotness(SSTableReader sstr) {
        // system tables don't have read meters, just use 0.0 for the hotness
        return sstr.readMeter == null ? 0.0 : sstr.readMeter.twoHourRate() / sstr.estimatedKeys();
    }
    
    private static Map<SSTableReader, Double> getHotnessMap(Collection<SSTableReader> sstables)
    {
        Map<SSTableReader, Double> hotness = new HashMap<>(sstables.size());
        for (SSTableReader sstable : sstables)
            hotness.put(sstable, hotness(sstable));
        return hotness;
    }

    static Pair<List<SSTableReader>, Double> trimToThresholdWithHotness(List<SSTableReader> bucket)
    {
        final Map<SSTableReader, Double> hotnessSnapshot = getHotnessMap(bucket);
        Collections.sort(bucket, new Comparator<SSTableReader>()
        {
            public int compare(SSTableReader o1, SSTableReader o2)
            {
                return -1 * Double.compare(hotnessSnapshot.get(o1), hotnessSnapshot.get(o2));
            }
        });

        double bucketHotness = 0.0;
        for (SSTableReader sstr : bucket)
            bucketHotness += hotness(sstr);

        return Pair.create(bucket, bucketHotness);
    }

    public static List<Pair<SSTableReader, Long>> createSSTableAndLengthPairs(Iterable<SSTableReader> sstables) {
        List<Pair<SSTableReader, Long>> sstableLengthPairs = new ArrayList<Pair<SSTableReader, Long>>(Iterables.size(sstables));
        for(SSTableReader sstable : sstables)
            sstableLengthPairs.add(Pair.create(sstable, sstable.bytesOnDisk()));
        return sstableLengthPairs;
    }

    public static List<SSTableReader> mostInterestingBucket(List<List<SSTableReader>> buckets) {
        final List<Pair<List<SSTableReader>, Double>> prunedBucketsAndHotness = new ArrayList<>(buckets.size());
        for (List<SSTableReader> bucket : buckets) {
            Pair<List<SSTableReader>, Double> bucketAndHotness = trimToThresholdWithHotness(bucket);
            if (bucketAndHotness != null)
                prunedBucketsAndHotness.add(bucketAndHotness);
        }
        if (prunedBucketsAndHotness.isEmpty())
            return Collections.emptyList();

        Pair<List<SSTableReader>, Double> hottest = Collections.max(prunedBucketsAndHotness, bucketsByHotnessComparator);
        return hottest.left;
    }

    private void updateEstimatedCompactionsByTasks(List<List<SSTableReader>> tasks)
    {
        int n = 0;
        for (List<SSTableReader> bucket: tasks)
        {
            if (bucket.size() >= cfs.getMinimumCompactionThreshold())
                n += Math.ceil((double)bucket.size() / cfs.getMaximumCompactionThreshold());
        }
        estimatedRemainingTasks = n;
    }

    private List<SSTableReader> getNextBackgroundSSTables(int gcBefore) {

            if (!isEnabled()) return Collections.emptyList();
        
        Iterable<SSTableReader> candidates = filterSuspectSSTables(cfs.getUncompactingSSTables());

        List<List<SSTableReader>> buckets = getBuckets(createSSTableAndLengthPairs(candidates));
        logger.debug("Compaction buckets are {}", buckets);

        updateEstimatedCompactionsByTasks(buckets);
        List<SSTableReader> mostInteresting = mostInterestingBucket(buckets);
        return mostInteresting;
    }

    @Override
    public AbstractCompactionTask getNextBackgroundTask(int gcBefore) {
        if (!isEnabled()) return null;

        while (true)
        {
            List<SSTableReader> hottestBucket = getNextBackgroundSSTables(gcBefore);

            if (hottestBucket.isEmpty()) return null;

            if (cfs.getDataTracker().markCompacting(hottestBucket))
                return new CompactionTask(cfs, hottestBucket, gcBefore, false);
        }
    }

    @Override
    public Collection<AbstractCompactionTask> getMaximalTask(int gcBefore) {
        
        Iterable<SSTableReader> allSSTables = cfs.markAllCompacting();
        if (allSSTables == null || Iterables.isEmpty(allSSTables)) 
            return null;

        Set<SSTableReader> sstables = Sets.newHashSet(allSSTables);
        Set<SSTableReader> repaired = new HashSet<>();
        Set<SSTableReader> unrepaired = new HashSet<>();
        for (SSTableReader sstable : sstables) {
            if (sstable.isRepaired()) {
                repaired.add(sstable);
            } else {
                unrepaired.add(sstable);
            }
        }
        return Arrays.<AbstractCompactionTask>asList(new CompactionTask(cfs, repaired, gcBefore, false), new CompactionTask(cfs, unrepaired, gcBefore, false));
    }

    @Override
    public AbstractCompactionTask getUserDefinedTask(
            Collection<SSTableReader> sstables, int gcBefore) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getEstimatedRemainingTasks() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long getMaxSSTableBytes() {
        return Long.MAX_VALUE;
    }

}
