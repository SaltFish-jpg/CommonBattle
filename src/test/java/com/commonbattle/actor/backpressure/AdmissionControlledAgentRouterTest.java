package com.commonbattle.actor.backpressure;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdmissionControlledAgentRouterTest {
    @Test
    void rejectedAdmissionDoesNotDeliverToMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                local,
                actors,
                directory,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC)
        );
        lifecycles.activate(AgentIdentity.player(10001L), "player-10001");
        executor.runNext();
        AdmissionControlledAgentRouter router = new AdmissionControlledAgentRouter(
                (target, operation) -> AdmissionDecision.reject("rate_limited", java.time.Duration.ofMillis(100)),
                new LifecycleAwareAgentRouter(lifecycles, new DefaultAgentMessagePort(actors, new NoopRpcGateway()))
        );
        AtomicInteger runs = new AtomicInteger();

        AdmissionRouteResult result = router.tellLocalOrRoute(
                AgentIdentity.player(10001L),
                "bag.use",
                ignored -> runs.incrementAndGet()
        );

        assertFalse(result.admission().accepted());
        assertEquals(AgentRouteType.MISSING, result.route().type());
        assertEquals(0, executor.queued());
        assertEquals(0, runs.get());
    }

    @Test
    void acceptedAdmissionRoutesLocalTask() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                local,
                actors,
                directory,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC)
        );
        lifecycles.activate(AgentIdentity.player(10001L), "player-10001");
        executor.runNext();
        AdmissionControlledAgentRouter router = new AdmissionControlledAgentRouter(
                (target, operation) -> AdmissionDecision.accept(),
                new LifecycleAwareAgentRouter(lifecycles, new DefaultAgentMessagePort(actors, new NoopRpcGateway()))
        );
        AtomicInteger runs = new AtomicInteger();

        AdmissionRouteResult result = router.tellLocalOrRoute(
                AgentIdentity.player(10001L),
                "bag.use",
                ignored -> runs.incrementAndGet()
        );
        executor.runNext();

        assertEquals(AgentRouteType.LOCAL, result.route().type());
        assertEquals(1, runs.get());
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        int queued() {
            return commands.size();
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
