package com.commonbattle.game.config;

import java.time.Clock;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存版游戏配置注册表。
 * 适合单服样例和测试；生产环境可替换为中心服配置仓库并通过事件推送到各业务进程。
 */
public final class InMemoryGameConfigRegistry implements GameConfigRegistry {
    private final GameConfigValidator validator;
    private final Clock clock;
    private final NavigableMap<Long, GameConfigRuntime> runtimes = new TreeMap<>();
    private final AtomicReference<GameConfigRuntime> active = new AtomicReference<>();
    private volatile GameConfigGrayRule grayRule;

    public InMemoryGameConfigRegistry(GameConfigValidator validator, Clock clock) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized GameConfigPublishResult publish(GameConfigPackage config) {
        Objects.requireNonNull(config, "config");
        GameConfigValidation validation = validator.validate(config);
        if (!validation.valid()) {
            return GameConfigPublishResult.rejected(config.version(), validation, "config validation failed");
        }
        GameConfigRuntime current = active.get();
        if (current != null && config.version() <= current.version()) {
            return GameConfigPublishResult.rejected(config.version(), validation, "version must be greater than active");
        }
        GameConfigRuntime runtime = GameConfigRuntime.from(config);
        runtimes.put(runtime.version(), runtime);
        active.set(runtime);
        grayRule = null;
        return GameConfigPublishResult.published(runtime.version());
    }

    @Override
    public synchronized GameConfigPublishResult publishGray(GameConfigPackage config, int percent) {
        Objects.requireNonNull(config, "config");
        GameConfigGrayRule rule = new GameConfigGrayRule(config.version(), percent);
        GameConfigValidation validation = validator.validate(config);
        if (!validation.valid()) {
            return GameConfigPublishResult.rejected(config.version(), validation, "config validation failed");
        }
        GameConfigRuntime current = active.get();
        if (current == null) {
            return GameConfigPublishResult.rejected(config.version(), validation, "active config missing");
        }
        if (config.version() <= current.version()) {
            return GameConfigPublishResult.rejected(config.version(), validation, "gray version must be greater than active");
        }
        GameConfigRuntime runtime = GameConfigRuntime.from(config);
        runtimes.put(runtime.version(), runtime);
        grayRule = rule;
        return GameConfigPublishResult.grayPublished(runtime.version());
    }

    @Override
    public synchronized GameConfigPublishResult rollback(long version) {
        GameConfigRuntime runtime = runtimes.get(version);
        if (runtime == null) {
            return GameConfigPublishResult.rejected(version, new GameConfigValidation(List.of()),
                    "rollback target version missing");
        }
        active.set(runtime);
        grayRule = null;
        return GameConfigPublishResult.rolledBack(version);
    }

    @Override
    public GameConfigRuntime active() {
        GameConfigRuntime runtime = active.get();
        if (runtime == null) {
            throw new IllegalStateException("active game config missing at " + clock.instant());
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
}
