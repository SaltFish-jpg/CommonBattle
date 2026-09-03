package com.commonbattle.actor.agent;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorOverflowStrategy;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorSystemConfig;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRouterTest {
    @Test
    void localOwnerIsDeliveredToActorMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ActorRef ref = actors.actor("player-10001");
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        directory.claim(AgentIdentity.player(10001L), new AgentLocation(local, ref));
        AgentRouter router = new AgentRouter(local, directory, new DefaultAgentMessagePort(actors, new NoopRpcGateway()));
        AtomicInteger runs = new AtomicInteger();

        AgentRoute route = router.tellLocalOrRoute(AgentIdentity.player(10001L), ignored -> runs.incrementAndGet());

        assertEquals(AgentRouteType.LOCAL, route.type());
        assertEquals(0, runs.get());
        executor.runNext();
        assertEquals(1, runs.get());
    }

    @Test
    void remoteOwnerReturnsRouteWithoutRunningLocalTask() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        ServiceId local = ServiceId.of(ServiceKind.SCENE, "r1", "scene-1");
        ServiceId remote = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        directory.claim(AgentIdentity.player(10001L), new AgentLocation(remote, new ActorRef("player-10001")));
        AgentRouter router = new AgentRouter(local, directory, new DefaultAgentMessagePort(actors, new NoopRpcGateway()));
        ActorTask task = ignored -> {
            throw new AssertionError("remote task must not run locally");
        };

        AgentRoute route = router.tellLocalOrRoute(AgentIdentity.player(10001L), task);

        assertEquals(AgentRouteType.REMOTE, route.type());
        assertEquals(remote, route.location().orElseThrow().serviceId());
        assertEquals(0, executor.queued());
    }

    @Test
    void deliverLocalOrRouteReportsMailboxFull() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(
                executor,
                new ActorSystemConfig(1, 64, 1, ActorOverflowStrategy.REJECT, java.time.Duration.ZERO),
                ignored -> {
                },
                ignored -> {
                }
        );
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ActorRef ref = actors.actor("player-10001");
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        directory.claim(AgentIdentity.player(10001L), new AgentLocation(local, ref));
        AgentRouter router = new AgentRouter(local, directory, new DefaultAgentMessagePort(actors, new NoopRpcGateway()));

        actors.send(ref, ignored -> {
        });
        AgentRouteDeliveryResult result = router.deliverLocalOrRoute(AgentIdentity.player(10001L), ignored -> {
        });

        assertEquals(AgentRouteType.LOCAL, result.route().type());
        assertEquals(AgentDeliveryStatus.MAILBOX_FULL, result.delivery().status());
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
