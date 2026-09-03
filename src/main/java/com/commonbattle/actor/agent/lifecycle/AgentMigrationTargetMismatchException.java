package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

/**
 * 迁入目标与目录中的 owner 不一致。
 */
public final class AgentMigrationTargetMismatchException extends RuntimeException {
    public AgentMigrationTargetMismatchException(AgentIdentity identity, AgentLocation expected) {
        super("Agent " + identity + " is not assigned to migration target " + expected);
    }
}
