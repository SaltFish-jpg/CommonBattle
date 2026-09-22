package com.commonbattle.actor.agent.migration;

import java.util.Optional;

enum NoopAgentMigrationTargetReceiptStore implements AgentMigrationTargetReceiptStore {
    INSTANCE;

    @Override
    public Optional<AgentMigrationAcceptResponse> find(String taskId) {
        return Optional.empty();
    }

    @Override
    public void save(String taskId, AgentMigrationAcceptResponse response) {
    }
}
