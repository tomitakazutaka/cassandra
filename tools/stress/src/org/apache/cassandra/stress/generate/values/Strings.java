/*
 *
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
 *
 */
package org.apache.cassandra.stress.generate.values;

import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.stress.generate.FasterRandom;

public class Strings extends Generator<String>
{
    private final char[] chars;
    private final FasterRandom rnd = new FasterRandom();

    public Strings(String name, GeneratorConfig config)
    {
        super(UTF8Type.instance, config, name, String.class);
        chars = new char[(int) sizeDistribution.maxValue()];
    }

    @Override
    public String generate()
    {
        long seed = identityDistribution.next();
        sizeDistribution.setSeed(seed);
        rnd.setSeed(~seed);
        int size = (int) sizeDistribution.next();
        for (int i = 0; i < size; )
            for (long v = rnd.nextLong(),
                 n = Math.min(size - i, Long.SIZE/Byte.SIZE);
                 n-- > 0; v >>= Byte.SIZE)
                chars[i++] = (char) (((v & 127) + 32) & 127);
        return new String(chars, 0, size);
    }
}
