package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.AgentLeaseConfig;
import com.commonbattle.actor.agent.AgentLeaseSupervisor;
import com.commonbattle.actor.agent.LeasedAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ServiceDescriptor;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Game、Scene、Chat 共用的 Agent 租约生命周期启动装配。
 */
final class BootAgentLifecycles {
    private BootAgentLifecycles() {
    }

    static AgentLifecycleManager configure(
            BootRuntime runtime,
            ClusterNodeConfig config,
            ServiceDescriptor local,
            ActorSystem actors,
            AgentDirectory directory,
            Clock clock
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(actors, "actors");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(clock, "clock");
        AgentLeaseConfig leaseConfig = config.agentLeaseConfig();
        if (!leaseConfig.enabled()) {
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local.id(), actors, directory, clock);
            runtime.observe("agentLifecycles", lifecycles);
            return lifecycles;
        }
        if (!(directory instanceof LeasedAgentDirectory leasedDirectory)) {
            throw new IllegalStateException("Agent lease is enabled but directory does not support leases");
        }
        AtomicReference<AgentLifecycleManager> lifecycleRef = new AtomicReference<>();
        AgentLeaseSupervisor supervisor = runtime.add(
                "agentLeaseSupervisor",
                new AgentLeaseSupervisor(
                        leasedDirectory,
                        AgentLeaseSupervisor.newOwnerSession(local.id().wireName()),
                        leaseConfig.leaseTtl(),
                        leaseConfig.renewInterval(),
                        clock,
                        lease -> {
                            AgentLifecycleManager lifecycles = lifecycleRef.get();
                            if (lifecycles != null) {
                                lifecycles.handleLeaseLost(lease);
                            }
                        }
                )
        );
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                local.id(), actors, directory, clock, supervisor
        );
        lifecycleRef.set(lifecycles);
        supervisor.start();
        runtime.observe("agentLifecycles", lifecycles);
        runtime.observe("agentLeaseSupervisorStats", supervisor);
        return lifecycles;
    }
}
