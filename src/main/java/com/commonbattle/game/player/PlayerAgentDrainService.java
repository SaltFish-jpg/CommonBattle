package com.commonbattle.game.player;

import com.commonbattle.runtime.DrainableComponent;

import java.util.Objects;
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
        submitted.set(agents.passivateAllAndSave(new PlayerStateSaveCallback() {
            @Override
            public void saved(PlayerStateSnapshot snapshot) {
                completed.incrementAndGet();
            }

            @Override
            public void failed(long playerId, RuntimeException error) {
                failedSaves.incrementAndGet();
            }
        }));
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
}
