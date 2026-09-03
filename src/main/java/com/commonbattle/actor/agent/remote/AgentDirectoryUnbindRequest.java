package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

/**
 * Agent owner 条件解绑请求。
 */
public record AgentDirectoryUnbindRequest(AgentIdentity identity, AgentLocation location) {
}
