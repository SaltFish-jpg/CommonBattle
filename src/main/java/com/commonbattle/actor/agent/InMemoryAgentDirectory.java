package com.commonbattle.actor.agent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 Agent 目录。
 * 它提供 owner 唯一性和 CAS 迁移语义，生产环境可替换为中心服务或一致性存储实现。
 */
public final class InMemoryAgentDirectory implements AgentDirectory {
    private final Map<AgentIdentity, AgentLocation> locations = new ConcurrentHashMap<>();

    @Override
    public boolean claim(AgentIdentity identity, AgentLocation location) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        return locations.compute(identity, (ignored, current) -> current == null ? location : current).equals(location);
    }

    @Override
    public boolean move(AgentIdentity identity, AgentLocation expectedCurrent, AgentLocation next) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        Objects.requireNonNull(next, "next");
        AgentLocation result = locations.computeIfPresent(identity, (ignored, current) ->
                current.equals(expectedCurrent) ? next : current);
        return next.equals(result);
    }

    @Override
    public void unbind(AgentIdentity identity, AgentLocation location) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        locations.remove(identity, location);
    }

    @Override
    public Optional<AgentLocation> locate(AgentIdentity identity) {
        return Optional.ofNullable(locations.get(identity));
    }
}
