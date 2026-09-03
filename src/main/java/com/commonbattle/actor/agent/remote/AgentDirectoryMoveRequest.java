package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

/**
 * Agent owner CAS 迁移请求。
 */
public record AgentDirectoryMoveRequest(
        AgentIdentity identity,
        AgentLocation expectedCurrent,
        AgentLocation next
) {
}
