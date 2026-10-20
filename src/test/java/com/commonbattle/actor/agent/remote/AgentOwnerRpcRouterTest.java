package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.SerializedAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentAlreadyOwnedException;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentOwnerRpcRouterTest {
    @Test
    void forwardsToExactRemoteOwnerWithoutRunningLocalBusiness() {
        Fixture fixture = new Fixture();
        AgentIdentity channel = new AgentIdentity("chat-channel", "world:1");
        fixture.directory.claim(channel, new AgentLocation(fixture.chatTwo.id(), new ActorRef("channel-world-1")));
        AtomicReference<String> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        fixture.client.call(
                RpcRequest.toService(fixture.chatOne.id(), Fixture.OPERATION, "hello", String.class),
                callback(response, failure, completed)
        );

        await(completed);
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertEquals("chat-2:hello", response.get());
        assertEquals(0, fixture.localRuns);
    }

    @Test
    void relocatesAndForwardsWhenAnotherNodeWinsActivationRace() {
        Fixture fixture = new Fixture();
        AgentIdentity channel = new AgentIdentity("chat-channel", "world:1");
        fixture.beforeLocalResponse = () -> {
            fixture.directory.claim(
                    channel,
                    new AgentLocation(fixture.chatTwo.id(), new ActorRef("channel-world-1"))
            );
            throw new AgentAlreadyOwnedException(channel);
        };
        AtomicReference<String> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        fixture.client.call(
                RpcRequest.toService(fixture.chatOne.id(), Fixture.OPERATION, "race", String.class),
                callback(response, failure, completed)
        );

        await(completed);
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertEquals("chat-2:race", response.get());
        assertEquals(1, fixture.localRuns);
    }

    private static RpcCallback<String> callback(
            AtomicReference<String> response,
            AtomicReference<Throwable> failure,
            CountDownLatch completed
    ) {
        return new RpcCallback<>() {
            @Override
            public void success(String value) {
                response.set(value);
                completed.countDown();
            }

            @Override
            public void failure(Throwable error) {
                failure.set(error);
                completed.countDown();
            }
        };
    }

    private static void await(CountDownLatch completed) {
        try {
            if (!completed.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("RPC did not complete");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class Fixture {
        private static final String OPERATION = "entity.echo";

        private final LocalClusterTransport transport = new LocalClusterTransport();
        private final ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.CHAT)
                .allow(ServiceKind.CHAT, ServiceKind.CENTER)
                .allow(ServiceKind.CENTER, ServiceKind.CHAT)
                .allow(ServiceKind.CHAT, ServiceKind.GAME)
                .allow(ServiceKind.CHAT, ServiceKind.CHAT);
        private final ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                AgentDirectoryOperations.CLAIM,
                AgentDirectoryOperations.LOCATE
        ));
        private final ServiceDescriptor chatOne = descriptor(ServiceKind.CHAT, "chat-1", 9001, Set.of(OPERATION));
        private final ServiceDescriptor chatTwo = descriptor(ServiceKind.CHAT, "chat-2", 9002, Set.of(OPERATION));
        private final ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9003, Set.of());
        private final SerializedAgentDirectory directory = new SerializedAgentDirectory(
                new InMemoryAtomicBytesStore()
        );
        private final ClusterRpcGateway client;
        private int localRuns;
        private Runnable beforeLocalResponse = () -> {
        };

        private Fixture() {
            ClusterDirectory centerRoutes = routes(center, chatOne, chatTwo, game);
            ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerRoutes, topology, transport);
            new CenterAgentDirectoryEndpoint(directory).bind(centerGateway);

            ClusterDirectory chatOneRoutes = routes(center, chatOne, chatTwo, game);
            ClusterRpcGateway chatOneGateway = new ClusterRpcGateway(chatOne, chatOneRoutes, topology, transport);
            RemoteAgentDirectory remoteDirectory = new RemoteAgentDirectory(chatOneGateway);
            AgentOwnerRpcRouter router = new AgentOwnerRpcRouter(chatOne.id(), remoteDirectory, chatOneGateway);
            chatOneGateway.handle(OPERATION, (request, responder) -> router.route(
                    new AgentIdentity("chat-channel", "world:1"),
                    OPERATION,
                    request.payload(),
                    String.class,
                    responder,
                    ownerFailure -> {
                        localRuns++;
                        try {
                            beforeLocalResponse.run();
                            responder.success("chat-1:" + request.payload());
                        } catch (RuntimeException error) {
                            ownerFailure.accept(error);
                        }
                    }
            ));

            ClusterRpcGateway chatTwoGateway = new ClusterRpcGateway(
                    chatTwo, routes(center, chatOne, chatTwo, game), topology, transport
            );
            chatTwoGateway.handle(OPERATION, (request, responder) ->
                    responder.success("chat-2:" + request.payload()));

            client = new ClusterRpcGateway(game, routes(chatOne), topology, transport);
        }

        private static ClusterDirectory routes(ServiceDescriptor... services) {
            ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
            for (ServiceDescriptor service : services) {
                directory.seed(service);
            }
            return directory;
        }

        private static ServiceDescriptor descriptor(
                ServiceKind kind,
                String node,
                int port,
                Set<String> topics
        ) {
            return new ServiceDescriptor(
                    ServiceId.of(kind, "r1", node),
                    new ServiceEndpoint("127.0.0.1", port),
                    topics,
                    Map.of()
            );
        }
    }
}
