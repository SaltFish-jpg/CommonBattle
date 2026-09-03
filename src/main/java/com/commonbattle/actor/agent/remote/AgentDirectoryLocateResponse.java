package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentLocation;

import java.util.Optional;

/**
 * Agent owner 查询响应。
 */
public record AgentDirectoryLocateResponse(AgentLocation location) {
    public Optional<AgentLocation> located() {
        return Optional.ofNullable(location);
    }
}
