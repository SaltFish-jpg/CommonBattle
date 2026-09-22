package com.commonbattle.actor.agent.migration;

/**
 * 目标迁入回执序列化器。
 */
public interface AgentMigrationTargetReceiptSerializer {
    byte[] encode(AgentMigrationTargetReceipt receipt);

    AgentMigrationTargetReceipt decode(byte[] bytes);
}
