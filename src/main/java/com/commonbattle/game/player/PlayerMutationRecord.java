package com.commonbattle.game.player;

import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;

import java.util.List;
import java.util.Objects;

/**
 * 一次已提交的玩家聚合状态及尚未移交可靠 outbox 的领域事件。
 */
public record PlayerMutationRecord(
        PlayerStateSnapshot snapshot,
        List<PlayerDomainVersionedEvent> pendingEvents,
        PlayerBusinessResponseSnapshot responses,
        String ownerToken
) {
    public PlayerMutationRecord(PlayerStateSnapshot snapshot, List<PlayerDomainVersionedEvent> pendingEvents) {
        this(snapshot, pendingEvents, PlayerBusinessResponseSnapshot.empty(snapshot.playerId()), "");
    }

    public PlayerMutationRecord(
            PlayerStateSnapshot snapshot,
            List<PlayerDomainVersionedEvent> pendingEvents,
            PlayerBusinessResponseSnapshot responses
    ) {
        this(snapshot, pendingEvents, responses, "");
    }

    public PlayerMutationRecord {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(pendingEvents, "pendingEvents");
        Objects.requireNonNull(responses, "responses");
        Objects.requireNonNull(ownerToken, "ownerToken");
        if (responses.playerId() != snapshot.playerId()) {
            throw new IllegalArgumentException("response snapshot playerId mismatch");
        }
        pendingEvents = List.copyOf(pendingEvents);
        long previousRevision = 0;
        for (PlayerDomainVersionedEvent event : pendingEvents) {
            Objects.requireNonNull(event, "pending event");
            if (event.playerId() != snapshot.playerId()) {
                throw new IllegalArgumentException("pending event playerId mismatch");
            }
            if (event.revision() <= previousRevision || event.revision() > snapshot.eventRevision()) {
                throw new IllegalArgumentException("pending event revisions must be ordered within snapshot watermark");
            }
            previousRevision = event.revision();
        }
    }
}
