package com.commonbattle.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAtomicBytesStoreTest {
    @TempDir
    Path directory;

    @Test
    void storesBytesAcrossRecreatedInstancesAndScansPrefix() {
        FileAtomicBytesStore first = new FileAtomicBytesStore(directory);
        first.put("player:state:10001", bytes("one"));
        first.put("shop:order:1", bytes("other"));

        FileAtomicBytesStore second = new FileAtomicBytesStore(directory);
        List<AtomicBytesStore.Entry> entries = second.scanPrefix("player:state:");

        assertArrayEquals(bytes("one"), second.load("player:state:10001").orElseThrow());
        assertEquals(1, entries.size());
        assertEquals("player:state:10001", entries.getFirst().key());
    }

    @Test
    void compareAndSetAndDeleteRequireExpectedBytes() {
        FileAtomicBytesStore store = new FileAtomicBytesStore(directory);
        store.put("k", bytes("v1"));

        assertFalse(store.compareAndSet("k", bytes("bad"), bytes("v2")));
        assertTrue(store.compareAndSet("k", bytes("v1"), bytes("v2")));
        assertArrayEquals(bytes("v2"), store.load("k").orElseThrow());
        assertFalse(store.compareAndDelete("k", bytes("v1")));
        assertTrue(store.compareAndDelete("k", bytes("v2")));
        assertTrue(store.load("k").isEmpty());
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
