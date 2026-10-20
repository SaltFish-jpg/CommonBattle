package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentDirectoryLease;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.session.PlayerCommandSequencerSnapshot;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SerializedPlayerMutationRepositoryTest {
    private static final long PLAYER_ID = 101L;
    private static final Instant NOW = Instant.parse("2026-10-16T02:00:00Z");

    @Test
    void restoresStateAndPendingEventsAfterProcessReconstruction() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        SerializedPlayerMutationRepository first = repository(bytes, new InMemoryPlayerStateRepository());

        PlayerStateSnapshot snapshot = snapshot(1, 1);
        PlayerDomainVersionedEvent event = event(1);
        first.commit(snapshot, List.of(event));

        SerializedPlayerMutationRepository rebuilt = repository(bytes, new InMemoryPlayerStateRepository());

        assertEquals(snapshot, rebuilt.load(PLAYER_ID).orElseThrow());
        assertEquals(List.of(event), rebuilt.pendingEvents(PLAYER_ID));
        assertEquals(1, rebuilt.mutationRepositoryStats().records());
        assertEquals(1, rebuilt.mutationRepositoryStats().pendingEvents());
        PlayerMutationPendingRecord pending = rebuilt.pendingMutationRecords(10).getFirst();
        assertEquals(PLAYER_ID, pending.playerId());
        assertEquals(List.of("bag.changed"), pending.eventTypes());
    }

    @Test
    void removesPendingEventOnlyAfterOutboxHandoffConfirmation() {
        SerializedPlayerMutationRepository repository = repository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository()
        );
        repository.commit(snapshot(1, 1), List.of(event(1)));

        repository.markEventEnqueued(PLAYER_ID, 1);

        assertEquals(List.of(), repository.pendingEvents(PLAYER_ID));
        assertEquals(1, repository.load(PLAYER_ID).orElseThrow().eventRevision());
        assertEquals(1, repository.mutationRepositoryStats().enqueuedEvents());
    }

    @Test
    void regularSnapshotSavePreservesEventsWaitingForOutbox() {
        SerializedPlayerMutationRepository repository = repository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository()
        );
        PlayerDomainVersionedEvent event = event(1);
        repository.commit(snapshot(1, 1), List.of(event));

        repository.save(PLAYER_ID, snapshot(2, 1));

        assertEquals(List.of(event), repository.pendingEvents(PLAYER_ID));
        assertEquals(2, repository.load(PLAYER_ID).orElseThrow().revision());
    }

    @Test
    void rejectsSkippedOrStaleCommandRevision() {
        SerializedPlayerMutationRepository repository = repository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository()
        );
        repository.commit(snapshot(1, 0), List.of());

        assertThrows(IllegalStateException.class, () -> repository.commit(snapshot(3, 0), List.of()));
        assertThrows(IllegalStateException.class, () -> repository.commit(snapshot(0, 0), List.of()));
    }

    @Test
    void projectionFailureDoesNotLoseCommittedTruth() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        PlayerStateRepository failingProjection = new PlayerStateRepository() {
            @Override
            public Optional<PlayerStateSnapshot> load(Long key) {
                return Optional.empty();
            }

            @Override
            public void save(Long key, PlayerStateSnapshot snapshot) {
                throw new IllegalStateException("projection unavailable");
            }
        };
        SerializedPlayerMutationRepository repository = repository(bytes, failingProjection);

        repository.commit(snapshot(1, 1), List.of(event(1)));

        assertEquals(1, repository.load(PLAYER_ID).orElseThrow().revision());
        assertEquals(1, repository.pendingEvents(PLAYER_ID).size());
    }

    @Test
    void commitsCompletedResponseInSameRecordAsState() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        SerializedPlayerMutationRepository repository = repository(bytes, new InMemoryPlayerStateRepository());
        PlayerCommand command = new PlayerCommand(
                PLAYER_ID, "session-1", 1, 1, PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 1)
        );
        PlayerBusinessResponse response = PlayerBusinessResponse.success(command, PlayerBusinessAck.OK);

        repository.commit(
                snapshot(1, 0),
                List.of(),
                new PlayerBusinessResponseSnapshot(PLAYER_ID, List.of(response))
        );

        SerializedPlayerMutationRepository rebuilt = repository(bytes, new InMemoryPlayerStateRepository());
        assertEquals(List.of(response), rebuilt.loadResponses(PLAYER_ID).orElseThrow().responses());
        assertEquals(1, rebuilt.load(PLAYER_ID).orElseThrow().revision());
    }

    @Test
    void newerOwnerTokenFencesPreviousOwnerBeforeItsNextCommit() {
        SerializedPlayerMutationRepository repository = repository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository()
        );
        repository.acquireOwner(snapshot(0, 0), "owner-a");
        repository.acquireOwner(snapshot(0, 0), "owner-b");

        PlayerMutationConflictException conflict = assertThrows(
                PlayerMutationConflictException.class,
                () -> repository.commitOwned("owner-a", snapshot(1, 0), List.of())
        );
        PlayerMutationRecord committed = repository.commitOwned("owner-b", snapshot(1, 0), List.of());

        assertEquals(PlayerMutationConflictReason.OWNER_FENCED, conflict.reason());
        assertEquals(0, conflict.currentRevision());
        assertEquals("owner-b", committed.ownerToken());
        assertEquals(1, committed.snapshot().revision());
    }

    @Test
    void directoryFencingTokenCannotMoveBackwardOrDowngradeToLegacyOwner() {
        SerializedPlayerMutationRepository repository = repository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository()
        );
        String legacy = "legacy-owner";
        String leaseOne = ownerToken(1, "game-1-session");
        String leaseTwo = ownerToken(2, "game-2-session");
        repository.acquireOwner(snapshot(0, 0), legacy);
        repository.acquireOwner(snapshot(0, 0), leaseOne);
        repository.acquireOwner(snapshot(0, 0), leaseTwo);

        PlayerMutationConflictException stale = assertThrows(
                PlayerMutationConflictException.class,
                () -> repository.acquireOwner(snapshot(0, 0), leaseOne)
        );
        PlayerMutationConflictException downgrade = assertThrows(
                PlayerMutationConflictException.class,
                () -> repository.acquireOwner(snapshot(0, 0), legacy)
        );
        PlayerMutationConflictException sameFenceDifferentSession = assertThrows(
                PlayerMutationConflictException.class,
                () -> repository.acquireOwner(snapshot(0, 0), ownerToken(2, "game-2-restarted"))
        );

        assertEquals(PlayerMutationConflictReason.OWNER_FENCED, stale.reason());
        assertEquals(PlayerMutationConflictReason.OWNER_FENCED, downgrade.reason());
        assertEquals(PlayerMutationConflictReason.OWNER_FENCED, sameFenceDifferentSession.reason());
        assertEquals(leaseTwo, repository.loadRecord(PLAYER_ID).orElseThrow().ownerToken());
    }

    private static SerializedPlayerMutationRepository repository(
            InMemoryAtomicBytesStore bytes,
            PlayerStateRepository projection
    ) {
        return new SerializedPlayerMutationRepository(
                bytes,
                projection,
                new ProtostuffPlayerStateSnapshotSerializer()
        );
    }

    private static PlayerStateSnapshot snapshot(long revision, long eventRevision) {
        return new PlayerProfile(PLAYER_ID, NOW.minusSeconds(60))
                .snapshot(revision, eventRevision, NOW)
                .withCommandSequencer(PlayerCommandSequencerSnapshot.empty(PLAYER_ID));
    }

    private static PlayerDomainVersionedEvent event(long revision) {
        return new PlayerDomainVersionedEvent(
                PLAYER_ID,
                "bag.changed",
                "gold",
                1,
                revision,
                NOW
        );
    }

    private static String ownerToken(long fencingToken, String ownerSession) {
        return PlayerMutationOwnerToken.encode(new AgentDirectoryLease(
                AgentIdentity.player(PLAYER_ID),
                new AgentLocation(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-" + fencingToken),
                        new ActorRef("player-" + PLAYER_ID)
                ),
                ownerSession,
                fencingToken,
                NOW.plusSeconds(30)
        ));
    }
}
