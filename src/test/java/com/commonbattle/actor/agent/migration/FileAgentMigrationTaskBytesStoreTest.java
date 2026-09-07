package com.commonbattle.actor.agent.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAgentMigrationTaskBytesStoreTest {
    @TempDir
    Path directory;

    @Test
    void savedTasksSurviveStoreRecreation() {
        FileAgentMigrationTaskBytesStore first = new FileAgentMigrationTaskBytesStore(directory);
        first.save("migration/1", new byte[]{1, 2, 3});

        FileAgentMigrationTaskBytesStore second = new FileAgentMigrationTaskBytesStore(directory);

        assertArrayEquals(new byte[]{1, 2, 3}, second.load("migration/1").orElseThrow());
        assertEquals(1, second.loadAll().size());
    }

    @Test
    void compareAndSetAndDeleteRequireExpectedBytes() {
        FileAgentMigrationTaskBytesStore store = new FileAgentMigrationTaskBytesStore(directory);
        store.save("migration-2", new byte[]{1});

        assertFalse(store.compareAndSet("migration-2", new byte[]{9}, new byte[]{2}));
        assertArrayEquals(new byte[]{1}, store.load("migration-2").orElseThrow());
        assertTrue(store.compareAndSet("migration-2", new byte[]{1}, new byte[]{2}));
        assertArrayEquals(new byte[]{2}, store.load("migration-2").orElseThrow());
        assertFalse(store.compareAndDelete("migration-2", new byte[]{1}));
        assertTrue(store.compareAndDelete("migration-2", new byte[]{2}));
        assertTrue(store.load("migration-2").isEmpty());
    }

    @Test
    void loadedBytesAreDefensiveCopies() {
        FileAgentMigrationTaskBytesStore store = new FileAgentMigrationTaskBytesStore(directory);
        store.save("migration-3", new byte[]{1, 2, 3});

        byte[] loaded = store.load("migration-3").orElseThrow();
        loaded[0] = 9;
        List<byte[]> all = store.loadAll();
        all.getFirst()[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, store.load("migration-3").orElseThrow());
    }
}
