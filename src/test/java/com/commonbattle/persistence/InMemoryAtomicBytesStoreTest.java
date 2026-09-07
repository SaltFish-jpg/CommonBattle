package com.commonbattle.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAtomicBytesStoreTest {
    @Test
    void copiesValuesAndSupportsSingleKeyCas() {
        InMemoryAtomicBytesStore store = new InMemoryAtomicBytesStore();
        byte[] first = new byte[]{1};
        assertTrue(store.putIfAbsent("k1", first));
        first[0] = 9;

        byte[] loaded = store.load("k1").orElseThrow();
        assertArrayEquals(new byte[]{1}, loaded);
        loaded[0] = 8;

        assertTrue(store.compareAndSet("k1", new byte[]{1}, new byte[]{2}));
        assertFalse(store.compareAndSet("k1", new byte[]{1}, new byte[]{3}));
        assertArrayEquals(new byte[]{2}, store.load("k1").orElseThrow());
        assertTrue(store.compareAndDelete("k1", new byte[]{2}));
        assertTrue(store.load("k1").isEmpty());
    }

    @Test
    void scansByPrefixWithCopiedEntries() {
        InMemoryAtomicBytesStore store = new InMemoryAtomicBytesStore();
        store.put("shop:order:1", new byte[]{1});
        store.put("shop:order:2", new byte[]{2});
        store.put("other:1", new byte[]{3});

        List<AtomicBytesStore.Entry> entries = store.scanPrefix("shop:order:");
        entries.getFirst().bytes()[0] = 9;

        assertEquals(2, entries.size());
        assertEquals(3, store.size());
    }
}
