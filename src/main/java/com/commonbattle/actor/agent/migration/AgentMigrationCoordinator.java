package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentMigrationCompletion;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 源服务 Agent 迁移协调器。
 * 它把“源邮箱打包、目录 CAS move、定点通知目标接收、失败回滚”串成一个可复用流程。
 */
public final class AgentMigrationCoordinator {
    private final AgentLifecycleManager sourceLifecycles;
    private final AgentDirectory directory;
    private final RemoteAgentMigrationClient client;
    private final Executor completionExecutor;

    public AgentMigrationCoordinator(
            AgentLifecycleManager sourceLifecycles,
            AgentDirectory directory,
            RemoteAgentMigrationClient client,
            Executor completionExecutor
    ) {
        this.sourceLifecycles = Objects.requireNonNull(sourceLifecycles, "sourceLifecycles");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.client = Objects.requireNonNull(client, "client");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
    }

    public boolean migrate(AgentIdentity identity, AgentLocation target, AgentMigrationStatePacker packer) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(packer, "packer");
        AtomicReference<AgentMigrationSnapshot> snapshot = new AtomicReference<>();
        return sourceLifecycles.migrate(
                identity,
                target,
                context -> snapshot.set(Objects.requireNonNull(packer.pack(identity, target, context), "snapshot")),
                completion -> onSourceMoved(completion, snapshot.get())
        );
    }

    private void onSourceMoved(AgentMigrationCompletion completion, AgentMigrationSnapshot snapshot) {
        if (!completion.moved()) {
            return;
        }
        completionExecutor.execute(() -> acceptTargetOrRollback(completion, snapshot));
    }

    private void acceptTargetOrRollback(AgentMigrationCompletion completion, AgentMigrationSnapshot snapshot) {
        try {
            AgentMigrationAcceptResponse response = client.accept(
                    completion.target().serviceId(),
                    new AgentMigrationAcceptRequest(
                            completion.identity(),
                            completion.target().actorRef().id(),
                            snapshot.stateType(),
                            snapshot.stateBytes()
                    )
            );
            if (!response.accepted()) {
                rollback(completion);
            }
        } catch (RuntimeException e) {
            rollback(completion);
        }
    }

    private void rollback(AgentMigrationCompletion completion) {
        if (directory.move(completion.identity(), completion.target(), completion.source())) {
            sourceLifecycles.resumeAfterMigrationRollback(completion.identity(), completion.source());
        }
    }
}
