package com.commonbattle.game.player;

import com.commonbattle.runtime.DrainableComponent;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家 Agent 排水服务。
 * 停服摘流时提交所有已加载玩家的保存和钝化任务，具体保存仍在玩家邮箱内完成。
 */
public final class PlayerAgentDrainService implements DrainableComponent {
    private final PlayerGameAgentManager agents;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicInteger submitted = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failedSaves = new AtomicLong();
    private final AtomicLong pendingAsyncBlockedSaves = new AtomicLong();
    private final Set<Long> pendingAsyncBlockedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<Long> retryingPendingAsyncPlayers = ConcurrentHashMap.newKeySet();

    public PlayerAgentDrainService(PlayerGameAgentManager agents) {
        this.agents = Objects.requireNonNull(agents, "agents");
    }

    @Override
    public void beginDrain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        completed.set(0);
        failedSaves.set(0);
        pendingAsyncBlockedSaves.set(0);
        pendingAsyncBlockedPlayers.clear();
        retryingPendingAsyncPlayers.clear();
        submitted.set(agents.passivateAllAndSave(new PlayerStateSaveCallback() {
            @Override
            public void saved(PlayerStateSnapshot snapshot) {
                recordSaved(snapshot);
            }

            @Override
            public void failed(long playerId, RuntimeException error) {
                recordFailed(playerId, error);
            }
        }));
    }

    @Override
    public void onDrainPoll() {
        if (!draining.get() || pendingAsyncBlockedPlayers.isEmpty()) {
            return;
        }
        for (Long playerId : List.copyOf(pendingAsyncBlockedPlayers)) {
            retryPendingAsyncBlockedPlayer(playerId);
        }
    }

    @Override
    public void resumeAccepting() {
        draining.set(false);
    }

    @Override
    public boolean isDraining() {
        return draining.get();
    }

    public int submitted() {
        return submitted.get();
    }

    public long completed() {
        return completed.get();
    }

    public long failedSaves() {
        return failedSaves.get();
    }

    public long pendingAsyncBlockedSaves() {
        return pendingAsyncBlockedSaves.get();
    }

    private void retryPendingAsyncBlockedPlayer(long playerId) {
        if (!retryingPendingAsyncPlayers.add(playerId)) {
            return;
        }
        boolean accepted = agents.passivateAndSave(playerId, new PlayerStateSaveCallback() {
            @Override
            public void saved(PlayerStateSnapshot snapshot) {
                retryingPendingAsyncPlayers.remove(playerId);
                recordSaved(snapshot);
            }

            @Override
            public void failed(long failedPlayerId, RuntimeException error) {
                retryingPendingAsyncPlayers.remove(playerId);
                recordFailed(failedPlayerId, error);
            }
        });
        if (!accepted) {
            retryingPendingAsyncPlayers.remove(playerId);
            clearPendingAsyncBlockedPlayer(playerId, true);
        }
    }

    private void recordSaved(PlayerStateSnapshot snapshot) {
        clearPendingAsyncBlockedPlayer(snapshot.playerId(), true);
        completed.incrementAndGet();
    }

    private void recordFailed(long playerId, RuntimeException error) {
        if (error instanceof PendingAsyncPlayerCommandException) {
            if (pendingAsyncBlockedPlayers.add(playerId)) {
                failedSaves.incrementAndGet();
                pendingAsyncBlockedSaves.incrementAndGet();
            }
            return;
        }
        if (pendingAsyncBlockedPlayers.remove(playerId)) {
            decrement(pendingAsyncBlockedSaves);
            return;
        }
        failedSaves.incrementAndGet();
    }

    private void clearPendingAsyncBlockedPlayer(long playerId, boolean clearFailedSave) {
        if (pendingAsyncBlockedPlayers.remove(playerId)) {
            decrement(pendingAsyncBlockedSaves);
            if (clearFailedSave) {
                decrement(failedSaves);
            }
        }
    }

    private static void decrement(AtomicLong counter) {
        counter.updateAndGet(value -> Math.max(0, value - 1));
    }
}
