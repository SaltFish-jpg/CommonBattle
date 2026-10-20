package com.commonbattle.game.profile;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedProfileProjectionStoreTest {
    @Test
    void restoresSnapshotAndRevisionTogetherAfterProcessReconstruction() {
        InMemoryProfileProjectionBytesStore bytes = new InMemoryProfileProjectionBytesStore();
        SerializedProfileProjectionStore firstStore = new SerializedProfileProjectionStore(bytes, "SCENE:r1:scene-1");
        LocalProfileCache first = new LocalProfileCache(firstStore);
        first.apply(event(3, "avatar_3"));

        LocalProfileCache restored = new LocalProfileCache(
                new SerializedProfileProjectionStore(bytes, "SCENE:r1:scene-1")
        );

        assertEquals(3, restored.revisionOf(10001L));
        assertEquals("avatar_3", restored.get(10001L).orElseThrow().snapshot().appearance().avatar());
        assertTrue(restored.isStale(10001L));
    }

    @Test
    void namespacesIsolateNodesSharingOneTable() {
        InMemoryProfileProjectionBytesStore bytes = new InMemoryProfileProjectionBytesStore();
        new SerializedProfileProjectionStore(bytes, "SCENE:r1:scene-1")
                .save(new CachedProfile(snapshot(1, "avatar_1"), false));
        new SerializedProfileProjectionStore(bytes, "CHAT:r1:chat-1")
                .save(new CachedProfile(snapshot(2, "avatar_2"), false));

        List<CachedProfile> scene = new SerializedProfileProjectionStore(bytes, "SCENE:r1:scene-1").loadAll();
        List<CachedProfile> chat = new SerializedProfileProjectionStore(bytes, "CHAT:r1:chat-1").loadAll();

        assertEquals(1, scene.getFirst().snapshot().revision());
        assertEquals(2, chat.getFirst().snapshot().revision());
    }

    @Test
    void failedPersistenceDoesNotAdvanceMemoryCheckpoint() {
        ProfileProjectionStore failed = new ProfileProjectionStore() {
            @Override
            public void save(CachedProfile profile) {
                throw new IllegalStateException("database unavailable");
            }

            @Override
            public List<CachedProfile> loadAll() {
                return List.of();
            }
        };
        LocalProfileCache cache = new LocalProfileCache(failed);

        assertThrows(IllegalStateException.class, () -> cache.apply(event(1, "avatar_1")));

        assertEquals(0, cache.revisionOf(10001L));
        assertTrue(cache.get(10001L).isEmpty());
    }

    private static ProfileChangedEvent event(long revision, String avatar) {
        return new ProfileChangedEvent(10001L, Set.of(ProfileField.APPEARANCE), snapshot(revision, avatar));
    }

    private static PlayerProfileSnapshot snapshot(long revision, String avatar) {
        return new PlayerProfileSnapshot(
                10001L,
                "hero",
                20,
                new AppearanceSummary(avatar, "frame", "costume"),
                new AllianceBrief(100, "alliance", "badge"),
                new FriendBrief(3, 2),
                revision,
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }
}
