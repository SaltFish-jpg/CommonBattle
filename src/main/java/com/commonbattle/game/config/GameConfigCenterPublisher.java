package com.commonbattle.game.config;

import com.commonbattle.game.event.VersionedEventBus;
import com.commonbattle.game.event.VersionedEvent;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 中心配置发布器。
 * 先更新权威配置仓库，成功后再发布变更事件，订阅方据此刷新本地缓存。
 */
public final class GameConfigCenterPublisher {
    private final GameConfigRegistry registry;
    private final Consumer<VersionedEvent> events;
    private final AtomicLong nextRevision;
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
        this.registry = Objects.requireNonNull(registry, "registry");
        this.events = Objects.requireNonNull(events, "events");
        if (initialRevision < 0) {
            throw new IllegalArgumentException("initialRevision must not be negative");
        }
        this.nextRevision = new AtomicLong(initialRevision);
    }

    public GameConfigPublishResult publish(GameConfigPackage config) {
        GameConfigPublishResult result = registry.publish(config);
        if (result.status() == GameConfigPublishStatus.PUBLISHED) {
            activeConfig = config;
            grayConfig = null;
            grayPercent = 0;
            events.accept(GameConfigChangedEvent.activePublished(nextRevision.incrementAndGet(), config));
        }
        return result;
    }

    public GameConfigPublishResult publishGray(GameConfigPackage config, int percent) {
        GameConfigPublishResult result = registry.publishGray(config, percent);
        if (result.status() == GameConfigPublishStatus.GRAY_PUBLISHED) {
            grayConfig = config;
            grayPercent = percent;
            events.accept(GameConfigChangedEvent.grayPublished(nextRevision.incrementAndGet(), config, percent));
        }
        return result;
    }

    public GameConfigPublishResult rollback(long version) {
        GameConfigPackage config = registry.version(version)
                .map(GameConfigRuntime::source)
                .orElse(null);
        GameConfigPublishResult result = registry.rollback(version);
        if (result.status() == GameConfigPublishStatus.ROLLED_BACK && config != null) {
            activeConfig = config;
            grayConfig = null;
            grayPercent = 0;
            events.accept(GameConfigChangedEvent.rolledBack(nextRevision.incrementAndGet(), config));
        }
        return result;
    }

    public long eventRevision() {
        return nextRevision.get();
    }

    public GameConfigSnapshot snapshot() {
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
}
