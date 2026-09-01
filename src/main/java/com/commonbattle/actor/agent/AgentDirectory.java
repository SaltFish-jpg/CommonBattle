package com.commonbattle.actor.agent;

import java.util.Optional;

/**
 * Agent owner 定位目录。
 * Game、Scene、Chat 等服务通过它确认某个玩家、场景或联盟 Agent 当前由哪个服务承载。
 */
public interface AgentDirectory {
    boolean claim(AgentIdentity identity, AgentLocation location);

    boolean move(AgentIdentity identity, AgentLocation expectedCurrent, AgentLocation next);

    void unbind(AgentIdentity identity, AgentLocation location);

    Optional<AgentLocation> locate(AgentIdentity identity);
}
