package com.commonbattle.game.config;

import com.commonbattle.game.event.SubscriptionCheckpoint;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.event.VersionedEventBus;
import com.commonbattle.game.event.VersionedEventSubscriber;

import java.time.Clock;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 业务进程本地配置缓存。
 * 订阅中心配置事件后在本地构建运行时，玩家和场景 Agent 只从本地缓存读取配置。
 */
public final class LocalGameConfigCache implements GameConfigView, VersionedEventSubscriber, AutoCloseable {
    private final GameConfigValidator validator;
    private final Clock clock;
    private final SubscriptionCheckpoint checkpoint = new SubscriptionCheckpoint();
    private final NavigableMap<Long, GameConfigRuntime> runtimes = new TreeMap<>();
    private final AtomicReference<GameConfigRuntime> active = new AtomicReference<>();
    private final AutoCloseable subscription;
    private volatile GameConfigGrayRule grayRule;
    private volatile boolean stale;
    private volatile long gapRevision;
    private volatile GameConfigApplyResult lastResult;
    private volatile GameConfigRecoveryTrigger recoveryTrigger;

    public LocalGameConfigCache(GameConfigValidator validator, Clock clock) {
        this(validator, clock, null);
    }

    public LocalGameConfigCache(GameConfigValidator validator, Clock clock, VersionedEventBus bus) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recoveryTrigger = ignored -> {
        };
        if (bus == null) {
            subscription = null;
        } else {
            subscription = bus.subscribe(GameConfigChangedEvent.TOPIC, this);
        }
    }

    @Override
    public void onEvent(VersionedEvent event) {
        if (!(event instanceof GameConfigChangedEvent changed)) {
            return;
        }
        apply(changed);
    }

    public synchronized GameConfigApplyResult apply(GameConfigChangedEvent event) {
        Objects.requireNonNull(event, "event");
        SubscriptionDecision decision = checkpoint.inspect(event);
        if (decision == SubscriptionDecision.DUPLICATE_OR_OLD) {
            lastResult = GameConfigApplyResult.duplicateOrOld(event);
            return lastResult;
        }
        if (decision == SubscriptionDecision.GAP) {
            stale = true;
            gapRevision = event.revision();
            lastResult = GameConfigApplyResult.gap(event);
            recoveryTrigger.onGap(lastResult);
            return lastResult;
        }
        GameConfigValidation validation = validator.validate(event.config());
        if (!validation.valid()) {
            stale = true;
            lastResult = GameConfigApplyResult.rejected(event, "config validation failed");
            return lastResult;
        }
        GameConfigRuntime runtime = GameConfigRuntime.from(event.config());
        runtimes.put(runtime.version(), runtime);
        switch (event.changeType()) {
            case ACTIVE_PUBLISHED -> {
                active.set(runtime);
                grayRule = null;
            }
            case GRAY_PUBLISHED -> {
                if (active.get() == null) {
                    stale = true;
                    lastResult = GameConfigApplyResult.rejected(event, "active config missing");
                    return lastResult;
                }
                grayRule = new GameConfigGrayRule(runtime.version(), event.grayPercent());
            }
            case ROLLED_BACK -> {
                active.set(runtime);
                grayRule = null;
            }
        }
        checkpoint.markApplied(event);
        stale = false;
        gapRevision = 0;
        lastResult = GameConfigApplyResult.applied(event);
        return lastResult;
    }

    public synchronized GameConfigApplyResult applySnapshot(GameConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        long currentRevision = appliedEventRevision();
        if (snapshot.eventRevision() < currentRevision) {
            lastResult = new GameConfigApplyResult(GameConfigApplyStatus.DUPLICATE_OR_OLD, snapshot.eventRevision(),
                    snapshot.activeConfig().version(), "snapshot_duplicate_or_old");
            return lastResult;
        }
        if (stale && gapRevision > 0 && snapshot.eventRevision() < gapRevision) {
            lastResult = GameConfigApplyResult.recoveryFailed(snapshot.eventRevision(), "snapshot_older_than_gap");
            return lastResult;
        }
        GameConfigValidation activeValidation = validator.validate(snapshot.activeConfig());
        if (!activeValidation.valid()) {
            stale = true;
            lastResult = new GameConfigApplyResult(GameConfigApplyStatus.REJECTED, snapshot.eventRevision(),
                    snapshot.activeConfig().version(), "active config validation failed");
            return lastResult;
        }
        GameConfigRuntime activeRuntime = GameConfigRuntime.from(snapshot.activeConfig());
        GameConfigRuntime grayRuntime = null;
        if (snapshot.grayConfig() != null) {
            GameConfigValidation grayValidation = validator.validate(snapshot.grayConfig());
            if (!grayValidation.valid()) {
                stale = true;
                lastResult = new GameConfigApplyResult(GameConfigApplyStatus.REJECTED, snapshot.eventRevision(),
                        snapshot.grayConfig().version(), "gray config validation failed");
                return lastResult;
            }
            grayRuntime = GameConfigRuntime.from(snapshot.grayConfig());
        }
        // 缺口恢复边界：完整快照原子替换本地配置，并把事件检查点推进到中心返回的发布流水号。
        runtimes.clear();
        runtimes.put(activeRuntime.version(), activeRuntime);
        active.set(activeRuntime);
        if (grayRuntime == null) {
            grayRule = null;
        } else {
            runtimes.put(grayRuntime.version(), grayRuntime);
            grayRule = new GameConfigGrayRule(grayRuntime.version(), snapshot.grayPercent());
        }
        checkpoint.reset(GameConfigChangedEvent.OWNER_KEY, snapshot.eventRevision());
        stale = false;
        gapRevision = 0;
        lastResult = GameConfigApplyResult.recovered(snapshot);
        return lastResult;
    }

    public boolean stale() {
        return stale;
    }

    public boolean ready() {
        return active.get() != null && !stale;
    }

    public Optional<Long> activeVersion() {
        GameConfigRuntime runtime = active.get();
        return runtime == null ? Optional.empty() : Optional.of(runtime.version());
    }

    public Optional<Long> grayVersion() {
        GameConfigGrayRule rule = grayRule;
        return rule == null ? Optional.empty() : Optional.of(rule.version());
    }

    public long appliedEventRevision() {
        return checkpoint.revisionOf(GameConfigChangedEvent.OWNER_KEY);
    }

    public Optional<GameConfigApplyResult> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    public void attachRecoveryTrigger(GameConfigRecoveryTrigger trigger) {
        recoveryTrigger = Objects.requireNonNull(trigger, "trigger");
    }

    @Override
    public GameConfigRuntime active() {
        GameConfigRuntime runtime = active.get();
        if (runtime == null) {
            throw new IllegalStateException("local game config missing at " + clock.instant());
        }
        return runtime;
    }

    @Override
    public GameConfigRuntime resolve(long playerId) {
        GameConfigGrayRule rule = grayRule;
        if (rule != null && rule.matches(playerId)) {
            GameConfigRuntime gray = runtimes.get(rule.version());
            if (gray != null) {
                return gray;
            }
        }
        return active();
    }

    @Override
    public synchronized Optional<GameConfigRuntime> version(long version) {
        return Optional.ofNullable(runtimes.get(version));
    }

    @Override
    public synchronized List<Long> versions() {
        return List.copyOf(runtimes.navigableKeySet());
    }

    @Override
    public void close() throws Exception {
        if (subscription != null) {
            subscription.close();
        }
    }
}
