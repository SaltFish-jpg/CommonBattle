package com.commonbattle.actor.agent.migration;

import com.commonbattle.cluster.ServiceId;

/**
 * Agent 迁移目标接收客户端。
 * 默认实现走跨服 RPC，测试或特殊部署可替换为本地/容灾实现。
 */
public interface AgentMigrationClient {
    AgentMigrationAcceptResponse accept(ServiceId targetServiceId, AgentMigrationAcceptRequest request);
}
