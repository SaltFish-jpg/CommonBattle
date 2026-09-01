package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.agent.AgentIdentity;

/**
 * 生命周期操作要求 Agent 当前处于 ACTIVE，但实际并非可服务状态。
 */
public final class AgentNotActiveException extends RuntimeException {
    public AgentNotActiveException(AgentIdentity identity) {
        super("Agent is not active: " + identity.wireName());
    }
}
