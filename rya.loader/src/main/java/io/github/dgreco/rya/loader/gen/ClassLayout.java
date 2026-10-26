/*
 * Copyright 2026 David Greco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dgreco.rya.loader.gen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which class each entity index belongs to. Indexes are dealt into {@value #SLOTS} slots by
 * smooth weighted round-robin, so the classes interleave evenly and every class has a fixed share
 * of every block. Entity i has class {@code slot[i % 1000]}; the r-th entity of a class can be
 * found in O(1), which lets links point at random members of a class.
 */
public final class ClassLayout {
    public static final int SLOTS = 1000;

    private final EntityClass[] slotClass = new EntityClass[SLOTS];
    private final Map<EntityClass, int[]> slotsOf = new EnumMap<>(EntityClass.class);
    private final int[] posInClass = new int[SLOTS];

    /** @param weights per-mille weight of each class (normalized to {@value #SLOTS} slots) */
    public ClassLayout(final Map<EntityClass, Integer> weights) {
        final Map<EntityClass, Integer> counts = normalize(weights);
        final Map<EntityClass, Integer> current = new EnumMap<>(EntityClass.class);
        final Map<EntityClass, List<Integer>> slots = new EnumMap<>(EntityClass.class);
        for (final EntityClass c : counts.keySet()) {
            current.put(c, 0);
            slots.put(c, new ArrayList<>());
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            EntityClass best = null;
            for (final Map.Entry<EntityClass, Integer> e : counts.entrySet()) {
                final int value = current.get(e.getKey()) + e.getValue();
                current.put(e.getKey(), value);
                if (best == null || value > current.get(best)) {
                    best = e.getKey();
                }
            }
            current.put(best, current.get(best) - SLOTS);
            slotClass[slot] = best;
            posInClass[slot] = slots.get(best).size();
            slots.get(best).add(slot);
        }
        for (final Map.Entry<EntityClass, List<Integer>> e : slots.entrySet()) {
            slotsOf.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
    }

    /** Largest-remainder rounding of the weights to {@value #SLOTS} slots. */
    private static Map<EntityClass, Integer> normalize(final Map<EntityClass, Integer> weights) {
        final long total = weights.values().stream().mapToLong(Integer::longValue).sum();
        if (total <= 0) {
            throw new IllegalArgumentException("class weights must add up to more than 0");
        }
        final Map<EntityClass, Integer> counts = new EnumMap<>(EntityClass.class);
        final Map<EntityClass, Double> remainders = new EnumMap<>(EntityClass.class);
        int assigned = 0;
        for (final Map.Entry<EntityClass, Integer> e : weights.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            final double exact = (double) e.getValue() * SLOTS / total;
            final int floor = (int) Math.floor(exact);
            counts.put(e.getKey(), floor);
            remainders.put(e.getKey(), exact - floor);
            assigned += floor;
        }
        final List<EntityClass> byRemainder = new ArrayList<>(remainders.keySet());
        byRemainder.sort((a, b) -> Double.compare(remainders.get(b), remainders.get(a)));
        for (int i = 0; assigned < SLOTS; i++, assigned++) {
            final EntityClass c = byRemainder.get(i % byRemainder.size());
            counts.put(c, counts.get(c) + 1);
        }
        counts.values().removeIf(v -> v == 0);
        return counts;
    }

    public EntityClass classOf(final long entity) {
        return slotClass[(int) (entity % SLOTS)];
    }

    /** How many of the first {@code n} entities have class {@code c}. */
    public long count(final EntityClass c, final long n) {
        final int[] slots = slotsOf.get(c);
        if (slots == null) {
            return 0;
        }
        long count = (n / SLOTS) * slots.length;
        final long rest = n % SLOTS;
        for (final int s : slots) {
            if (s < rest) {
                count++;
            }
        }
        return count;
    }

    /** The rank of an entity among the members of its class. */
    public long rankOf(final long entity) {
        final int slot = (int) (entity % SLOTS);
        return (entity / SLOTS) * slotsOf.get(slotClass[slot]).length + posInClass[slot];
    }

    /** The entity index of the {@code rank}-th member of class {@code c}. */
    public long indexOf(final EntityClass c, final long rank) {
        final int[] slots = slotsOf.get(c);
        return (rank / slots.length) * SLOTS + slots[(int) (rank % slots.length)];
    }

    public boolean has(final EntityClass c) {
        return slotsOf.containsKey(c);
    }
}
