package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentIdentity;

/**
 * Agent owner 查询请求。
 */
public record AgentDirectoryLocateRequest(AgentIdentity identity) {
}
