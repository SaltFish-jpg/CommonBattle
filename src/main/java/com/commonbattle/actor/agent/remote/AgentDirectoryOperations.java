package com.commonbattle.actor.agent.remote;

/**
 * 中心化 AgentDirectory RPC 操作名。
 */
public final class AgentDirectoryOperations {
    public static final String CLAIM = "agent.directory.claim";
    public static final String MOVE = "agent.directory.move";
    public static final String UNBIND = "agent.directory.unbind";
    public static final String LOCATE = "agent.directory.locate";

    private AgentDirectoryOperations() {
    }
}
