package com.commonbattle.game.config;

import com.commonbattle.game.event.VersionedEventBus;
import com.commonbattle.game.event.VersionedEvent;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 中心配置发布器。
 * 先更新权威配置仓库，成功后再发布变更事件，订阅方据此刷新本地缓存。
 */
public final class GameConfigCenterPublisher {
    private final GameConfigRegistry registry;
    private final Consumer<VersionedEvent> events;
    private final GameConfigCenterStateStore stateStore;
    private final AtomicLong nextRevision;
    private final AtomicBoolean persistenceFailed = new AtomicBoolean();
    private volatile GameConfigPackage activeConfig;
    private volatile GameConfigPackage grayConfig;
    private volatile int grayPercent;

    public GameConfigCenterPublisher(GameConfigRegistry registry, VersionedEventBus events) {
        this(registry, events::publish, 0);
    }

    public GameConfigCenterPublisher(GameConfigRegistry registry, VersionedEventBus events, long initialRevision) {
        this(registry, events::publish, initialRevision);
    }

    public GameConfigCenterPublisher(GameConfigRegistry registry, Consumer<VersionedEvent> events) {
        this(registry, events, 0);
    }

    public GameConfigCenterPublisher(GameConfigRegistry registry, Consumer<VersionedEvent> events, long initialRevision) {
        this(registry, events, initialRevision, GameConfigCenterStateStore.noop(), null);
    }

    public GameConfigCenterPublisher(
            GameConfigRegistry registry,
            Consumer<VersionedEvent> events,
            GameConfigCenterStateStore stateStore,
            GameConfigSnapshot recovered
    ) {
        this(registry, events, recovered == null ? 0 : recovered.eventRevision(), stateStore, recovered);
    }

    private GameConfigCenterPublisher(
            GameConfigRegistry registry,
            Consumer<VersionedEvent> events,
            long initialRevision,
            GameConfigCenterStateStore stateStore,
            GameConfigSnapshot recovered
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.events = Objects.requireNonNull(events, "events");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        if (initialRevision < 0) {
            throw new IllegalArgumentException("initialRevision must not be negative");
        }
        this.nextRevision = new AtomicLong(initialRevision);
        if (recovered != null) {
            activeConfig = recovered.activeConfig();
            grayConfig = recovered.grayConfig();
            grayPercent = recovered.grayPercent();
        }
    }

    public synchronized GameConfigPublishResult publish(GameConfigPackage config) {
        ensureWritable();
        GameConfigPublishResult result = registry.publish(config);
        if (result.status() == GameConfigPublishStatus.PUBLISHED) {
            long revision = nextRevision.get() + 1;
            GameConfigSnapshot snapshot = GameConfigSnapshot.activeOnly(revision, config);
            persist(config, snapshot);
            activeConfig = config;
            grayConfig = null;
            grayPercent = 0;
            nextRevision.set(revision);
            events.accept(GameConfigChangedEvent.activePublished(revision, config));
        }
        return result;
    }

    public synchronized GameConfigPublishResult publishGray(GameConfigPackage config, int percent) {
        ensureWritable();
        GameConfigPublishResult result = registry.publishGray(config, percent);
        if (result.status() == GameConfigPublishStatus.GRAY_PUBLISHED) {
            long revision = nextRevision.get() + 1;
            GameConfigPackage active = activeConfig == null ? registry.active().source() : activeConfig;
            GameConfigSnapshot snapshot = GameConfigSnapshot.withGray(revision, active, config, percent);
            persist(config, snapshot);
            grayConfig = config;
            grayPercent = percent;
            nextRevision.set(revision);
            events.accept(GameConfigChangedEvent.grayPublished(revision, config, percent));
        }
        return result;
    }

    public synchronized GameConfigPublishResult rollback(long version) {
        ensureWritable();
        GameConfigPackage config = registry.version(version)
                .map(GameConfigRuntime::source)
                .orElse(null);
        GameConfigPublishResult result = registry.rollback(version);
        if (result.status() == GameConfigPublishStatus.ROLLED_BACK && config != null) {
            long revision = nextRevision.get() + 1;
            GameConfigSnapshot snapshot = GameConfigSnapshot.activeOnly(revision, config);
            persist(config, snapshot);
            activeConfig = config;
            grayConfig = null;
            grayPercent = 0;
            nextRevision.set(revision);
            events.accept(GameConfigChangedEvent.rolledBack(revision, config));
        }
        return result;
    }

    public long eventRevision() {
        return nextRevision.get();
    }

    public synchronized GameConfigSnapshot snapshot() {
        GameConfigPackage active = activeConfig;
        if (active == null) {
            active = registry.active().source();
        }
        GameConfigPackage gray = grayConfig;
        if (gray == null) {
            return GameConfigSnapshot.activeOnly(nextRevision.get(), active);
        }
        return GameConfigSnapshot.withGray(nextRevision.get(), active, gray, grayPercent);
    }

    private void persist(GameConfigPackage config, GameConfigSnapshot snapshot) {
        try {
            stateStore.saveVersion(config);
            stateStore.saveSnapshot(snapshot);
        } catch (RuntimeException error) {
            persistenceFailed.set(true);
            throw new IllegalStateException("Game config publisher entered fail-stop after persistence failure", error);
        }
    }

    private void ensureWritable() {
        if (persistenceFailed.get()) {
            throw new IllegalStateException("Game config publisher is fail-stopped after persistence failure");
        }
    }
}
