package com.commonbattle.game.config;

import com.commonbattle.cluster.event.EventReplayRepairer;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 配置事件 replay 缺口修补器。
 * replay 历史窗口覆盖时直接触发完整配置快照恢复，复用 GameConfigAutoRecovery 的并发保护和统计。
 */
public final class GameConfigEventReplayRepairer implements EventReplayRepairer {
    private final GameConfigAutoRecovery autoRecovery;
    private final LongSupplier currentRevision;
    private final BooleanSupplier repairNeeded;

    public GameConfigEventReplayRepairer(GameConfigAutoRecovery autoRecovery, LongSupplier currentRevision) {
        this(autoRecovery, currentRevision, () -> true);
    }

    public GameConfigEventReplayRepairer(
            GameConfigAutoRecovery autoRecovery,
            LongSupplier currentRevision,
            BooleanSupplier repairNeeded
    ) {
        this.autoRecovery = Objects.requireNonNull(autoRecovery, "autoRecovery");
        this.currentRevision = Objects.requireNonNull(currentRevision, "currentRevision");
        this.repairNeeded = Objects.requireNonNull(repairNeeded, "repairNeeded");
    }

    @Override
    public void repair(String topic, Set<String> ownerKeys) {
        if (!GameConfigChangedEvent.TOPIC.equals(topic)) {
            throw new IllegalArgumentException("Unsupported config replay repair topic " + topic);
        }
        if (!ownerKeys.contains(GameConfigChangedEvent.OWNER_KEY)) {
            return;
        }
        if (!repairNeeded.getAsBoolean()) {
            return;
        }
        autoRecovery.onGap(new GameConfigApplyResult(
                GameConfigApplyStatus.GAP,
                currentRevision.getAsLong(),
                0,
                "event_replay_window_loss"
        ));
    }
}
