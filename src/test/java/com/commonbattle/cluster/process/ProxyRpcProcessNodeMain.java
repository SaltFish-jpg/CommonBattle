package com.commonbattle.cluster.process;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.message.RemoteActorCallOptions;
import com.commonbattle.actor.message.RemoteActorCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.netty.NettyClusterTransportConfig;
import com.commonbattle.cluster.network.ForwardingProxy;
import com.commonbattle.cluster.network.ForwardingProxyConfig;
import com.commonbattle.cluster.network.ForwardingProxyStats;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.ResilientRpcGateway;
import com.commonbattle.cluster.rpc.RpcCircuitBreakerConfig;
import com.commonbattle.cluster.rpc.RpcGovernanceConfig;
import com.commonbattle.cluster.rpc.RpcRetryPolicy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 双代理多进程故障测试节点。
 * Scene 的延迟响应由定时器重新投递到 Scene Actor，测试期间仍保持业务状态的邮箱串行边界。
 */
public final class ProxyRpcProcessNodeMain {
    static final String OPERATION = "test.scene.proxy.echo";
    private static final long PLAYER_ID = 10_001L;

    private ProxyRpcProcessNodeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "usage: <GAME|SCENE|PROXY_A|PROXY_B> <gamePort> <scenePort> <proxyAPort> <proxyBPort>"
            );
        }
        Role role = Role.valueOf(args[0]);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-process", Integer.parseInt(args[1]));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-process", Integer.parseInt(args[2]));
        ServiceDescriptor proxyA = descriptor(ServiceKind.PROXY, "proxy-a", Integer.parseInt(args[3]));
        ServiceDescriptor proxyB = descriptor(ServiceKind.PROXY, "proxy-b", Integer.parseInt(args[4]));
        ServiceDescriptor local = switch (role) {
            case GAME -> game;
            case SCENE -> scene;
            case PROXY_A -> proxyA;
            case PROXY_B -> proxyB;
        };
        InMemoryServiceRegistry registry = registry(game, scene, proxyA, proxyB);
        ClusterDirectory directory = directory(registry);
        Map<ServiceId, ServiceEndpoint> endpoints = endpoints(game, scene, proxyA, proxyB);
        NettyClusterTransportConfig transportConfig = new NettyClusterTransportConfig(
                Duration.ofMillis(400), Duration.ofMillis(50), 64
        );
        RpcGovernanceConfig rpcConfig = new RpcGovernanceConfig(
                Duration.ofSeconds(3), 128, Duration.ofMillis(200), 128
        );
        PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        ScheduledExecutorService delayedResponses = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "proxy-process-scene-delay");
            thread.setDaemon(true);
            return thread;
        });

        try (ActorSystem actors = new ActorSystem(2, 32);
             NettyClusterTransport transport = new NettyClusterTransport(
                     endpoints::get, PayloadCodecRegistry.commonDefaults(), transportConfig
             );
             ClusterRpcGateway gateway = new ClusterRpcGateway(
                     local, directory, ClusterTopology.defaultCrossServer(), transport, false, rpcConfig
             );
             ResilientRpcGateway resilient = new ResilientRpcGateway(
                     gateway,
                     RpcRetryPolicy.noRetry(),
                     new RpcCircuitBreakerConfig(10, Duration.ofSeconds(1))
             );
             DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, resilient)) {
            ForwardingProxy forwarder = bind(role, local, transport, gateway);
            ActorRef actor = actors.actor(role == Role.GAME
                    ? "player-" + PLAYER_ID
                    : "scene-process-agent");
            if (role == Role.SCENE) {
                bindSceneHandler(actors, actor, gateway, delayedResponses, output);
            }
            emit(output, "CB_READY", role.name(), Long.toString(ProcessHandle.current().pid()));
            try {
                controlLoop(role, actors, actor, scene, messages, transport, gateway, resilient, forwarder, output);
            } finally {
                if (forwarder != null) {
                    forwarder.close();
                }
            }
        } finally {
            delayedResponses.shutdownNow();
        }
    }

    private static ForwardingProxy bind(
            Role role,
            ServiceDescriptor local,
            NettyClusterTransport transport,
            ClusterRpcGateway gateway
    ) {
        if (role != Role.PROXY_A && role != Role.PROXY_B) {
            transport.bind(local, gateway::onMessage);
            return null;
        }
        ForwardingProxy forwarder = new ForwardingProxy(
                transport,
                local,
                new ForwardingProxyConfig(Duration.ofMillis(500), 64)
        );
        transport.bind(local, envelope -> {
            if (com.commonbattle.cluster.network.ClusterDeliveryProtocol.isAcknowledgment(envelope)) {
                forwarder.onMessage(envelope);
            } else if (envelope.target().equals(local.id())) {
                gateway.onMessage(envelope);
            } else {
                forwarder.onMessage(envelope);
            }
        });
        return forwarder;
    }

    private static void controlLoop(
            Role role,
            ActorSystem actors,
            ActorRef actor,
            ServiceDescriptor scene,
            DefaultAgentMessagePort messages,
            NettyClusterTransport transport,
            ClusterRpcGateway gateway,
            ResilientRpcGateway resilient,
            ForwardingProxy forwarder,
            PrintWriter output
    ) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            String[] command = line.split("\\|", 4);
            if ("EXIT".equals(command[0])) {
                return;
            }
            if ("CALL".equals(command[0]) && role == Role.GAME && command.length == 4) {
                submitCall(
                        actors, actor, scene, messages, command[1], command[2], Long.parseLong(command[3]), output
                );
                continue;
            }
            if ("STATS".equals(command[0]) && command.length >= 2) {
                emitStats(role, command[1], transport, gateway, resilient, forwarder, output);
                continue;
            }
            emit(output, "CB_ERROR", line);
        }
    }

    private static void submitCall(
            ActorSystem actors,
            ActorRef player,
            ServiceDescriptor scene,
            DefaultAgentMessagePort messages,
            String requestId,
            String payload,
            long delayMillis,
            PrintWriter output
    ) {
        long startedAt = System.nanoTime();
        actors.send(player, ignored -> messages.callRemote(
                player,
                RpcRequest.toService(
                        scene.id(), OPERATION, requestId + ":" + delayMillis + ":" + payload, String.class
                ),
                RemoteActorCallOptions.of(Duration.ofSeconds(7)),
                new RemoteActorCallback<>() {
                    @Override
                    public void success(ActorContext context, String response) {
                        emitResult(output, requestId, "SUCCESS", response, player, startedAt);
                    }

                    @Override
                    public void failure(ActorContext context, AgentDeliveryResult delivery, Throwable error) {
                        emit(output,
                                "CB_RESULT",
                                requestId,
                                "FAILURE",
                                delivery.status().name(),
                                error.getClass().getSimpleName(),
                                Thread.currentThread().getName(),
                                ActorContext.requireCurrent(player).self().id(),
                                elapsedMillis(startedAt)
                        );
                    }
                }
        ));
    }

    private static void bindSceneHandler(
            ActorSystem actors,
            ActorRef scene,
            ClusterRpcGateway gateway,
            ScheduledExecutorService delayedResponses,
            PrintWriter output
    ) {
        gateway.handle(OPERATION, (request, responder) -> {
            String payload = (String) request.payload();
            String[] fields = payload.split(":", 3);
            String requestId = fields[0];
            long delayMillis = Long.parseLong(fields[1]);
            Runnable enterMailbox = () -> actors.trySend(scene, ignored -> {
                emit(output,
                        "CB_SCENE_HANDLED",
                        requestId,
                        Thread.currentThread().getName(),
                        ActorContext.requireCurrent(scene).self().id()
                );
                responder.success("echo:" + payload);
            });
            boolean accepted = actors.trySend(scene, ignored -> {
                emit(output,
                        "CB_SCENE_ACCEPTED",
                        requestId,
                        Thread.currentThread().getName(),
                        ActorContext.requireCurrent(scene).self().id()
                );
                if (delayMillis <= 0) {
                    responder.success("echo:" + payload);
                } else {
                    delayedResponses.schedule(enterMailbox, delayMillis, TimeUnit.MILLISECONDS);
                }
            });
            if (!accepted) {
                responder.failure(new IllegalStateException("scene actor mailbox rejected request"));
            }
        });
    }

    private static void emitResult(
            PrintWriter output,
            String requestId,
            String status,
            String response,
            ActorRef player,
            long startedAt
    ) {
        emit(output,
                "CB_RESULT",
                requestId,
                status,
                response,
                Thread.currentThread().getName(),
                ActorContext.requireCurrent(player).self().id(),
                elapsedMillis(startedAt)
        );
    }

    private static void emitStats(
            Role role,
            String requestId,
            NettyClusterTransport transport,
            ClusterRpcGateway gateway,
            ResilientRpcGateway resilient,
            ForwardingProxy forwarder,
            PrintWriter output
    ) {
        var network = transport.stats();
        ForwardingProxyStats forwarding = forwarder == null ? ForwardingProxyStats.empty() : forwarder.stats();
        emit(output,
                "CB_STATS",
                requestId,
                role.name(),
                Integer.toString(network.activeConnections()),
                Long.toString(network.connectionAttempts()),
                Long.toString(network.connectionFailures()),
                Integer.toString(gateway.stats().pendingRequests()),
                Long.toString(network.inFlightWrites()),
                Long.toString(resilient.stats().retries()),
                Long.toString(forwarding.receivedEnvelopes()),
                Long.toString(forwarding.forwardedEnvelopes()),
                Long.toString(forwarding.failedForwards()),
                Long.toString(forwarding.sentFailureReceipts()),
                Long.toString(forwarding.failedFailureReceipts()),
                Long.toString(gateway.stats().replyFailovers()),
                Long.toString(gateway.stats().undeliverableReplies()),
                Long.toString(gateway.stats().requestFailovers()),
                Integer.toString(gateway.stats().pendingDeliveryAcks()),
                Integer.toString(forwarding.pendingForwards()),
                Long.toString(forwarding.timedOutForwards()),
                Long.toString(forwarding.sentDeliveryAcks())
        );
    }

    private static InMemoryServiceRegistry registry(ServiceDescriptor... services) {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        for (ServiceDescriptor service : services) {
            registry.register(service);
        }
        return registry;
    }

    private static ClusterDirectory directory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        directory.watch(ServiceKind.GAME);
        directory.watch(ServiceKind.SCENE);
        directory.watch(ServiceKind.PROXY);
        return directory;
    }

    private static Map<ServiceId, ServiceEndpoint> endpoints(ServiceDescriptor... services) {
        Map<ServiceId, ServiceEndpoint> endpoints = new HashMap<>();
        for (ServiceDescriptor service : services) {
            endpoints.put(service.id(), service.endpoint());
        }
        return Map.copyOf(endpoints);
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(OPERATION),
                Map.of()
        );
    }

    private static String elapsedMillis(long startedAt) {
        return Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private static void emit(PrintWriter output, String... fields) {
        synchronized (output) {
            output.println(String.join("|", fields));
        }
    }

    private enum Role {
        GAME,
        SCENE,
        PROXY_A,
        PROXY_B
    }
}
