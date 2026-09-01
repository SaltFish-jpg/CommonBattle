package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.agent.AgentIdentity;

/**
 * 激活 Agent 时目录中已存在其他 owner。
 */
public final class AgentAlreadyOwnedException extends RuntimeException {
    public AgentAlreadyOwnedException(AgentIdentity identity) {
        super("Agent already owned: " + identity.wireName());
    }
}
