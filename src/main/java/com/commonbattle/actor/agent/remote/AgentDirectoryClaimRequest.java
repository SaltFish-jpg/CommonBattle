package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

/**
 * Agent owner 抢占请求。
 */
public record AgentDirectoryClaimRequest(AgentIdentity identity, AgentLocation location) {
}
