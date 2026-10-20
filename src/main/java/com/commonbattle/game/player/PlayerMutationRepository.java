package com.commonbattle.game.player;

import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;

import java.util.List;

/**
 * 玩家命令的原子提交仓库。
 * 实现必须把完整状态与待发布事件写入同一个原子记录；事件移交 outbox 后再按 revision 确认。
 */
public interface PlayerMutationRepository extends PlayerStateRepository {
    /**
     * 为已通过 AgentDirectory 获得所有权的实例刷新 fencing token，并返回接管时的最新记录。
     */
    default java.util.Optional<PlayerMutationRecord> acquireOwner(long playerId, String ownerToken) {
        return loadRecord(playerId);
    }

    /**
     * 刷新 owner；记录不存在时用恢复基准创建首个带 fencing token 的原子记录。
     */
    default PlayerMutationRecord acquireOwner(PlayerStateSnapshot initialSnapshot, String ownerToken) {
        return acquireOwner(initialSnapshot.playerId(), ownerToken)
                .orElseGet(() -> new PlayerMutationRecord(initialSnapshot, List.of()));
    }

    PlayerMutationRecord commit(PlayerStateSnapshot snapshot, List<PlayerDomainVersionedEvent> events);

    default PlayerMutationRecord commitOwned(
            String ownerToken,
            PlayerStateSnapshot snapshot,
            List<PlayerDomainVersionedEvent> events
    ) {
        return commit(snapshot, events);
    }

    default PlayerMutationRecord commit(
            PlayerStateSnapshot snapshot,
            List<PlayerDomainVersionedEvent> events,
            PlayerBusinessResponseSnapshot responses
    ) {
        return commit(snapshot, events);
    }

    default PlayerMutationRecord commitOwned(
            String ownerToken,
            PlayerStateSnapshot snapshot,
            List<PlayerDomainVersionedEvent> events,
            PlayerBusinessResponseSnapshot responses
    ) {
        return commit(snapshot, events, responses);
    }

    PlayerMutationRecord markEventEnqueued(long playerId, long eventRevision);

    default List<PlayerDomainVersionedEvent> pendingEvents(long playerId) {
        return loadRecord(playerId).map(PlayerMutationRecord::pendingEvents).orElseGet(List::of);
    }

    java.util.Optional<PlayerMutationRecord> loadRecord(long playerId);

    default java.util.Optional<PlayerBusinessResponseSnapshot> loadResponses(long playerId) {
        return loadRecord(playerId).map(PlayerMutationRecord::responses);
    }

    default void saveResponses(PlayerBusinessResponseSnapshot responses) {
        throw new UnsupportedOperationException("response checkpoint is not supported");
    }
}
