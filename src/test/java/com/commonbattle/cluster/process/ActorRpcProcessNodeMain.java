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
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcGovernanceConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 多进程故障测试使用的最小 Actor/RPC 节点。
 * 控制协议只服务测试编排，业务请求仍完整经过 Netty、RPC 和 Actor mailbox。
 */
public final class ActorRpcProcessNodeMain {
    static final String OPERATION = "test.scene.process.echo";
    private static final long PLAYER_ID = 10_001L;

    private ActorRpcProcessNodeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: <GAME|SCENE> <gamePort> <scenePort>");
        }
        ServiceKind role = ServiceKind.valueOf(args[0]);
        int gamePort = Integer.parseInt(args[1]);
        int scenePort = Integer.parseInt(args[2]);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-process", gamePort);
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-process", scenePort);
        ServiceDescriptor local = role == ServiceKind.GAME ? game : scene;
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        registry.register(game);
        registry.register(scene);
        ClusterDirectory directory = new ClusterDirectory(registry);
        directory.watch(ServiceKind.GAME);
        directory.watch(ServiceKind.SCENE);
        Map<ServiceId, ServiceEndpoint> endpoints = Map.of(game.id(), game.endpoint(), scene.id(), scene.endpoint());
        NettyClusterTransportConfig transportConfig = new NettyClusterTransportConfig(
                Duration.ofMillis(500), Duration.ofMillis(50), 64);
        RpcGovernanceConfig rpcConfig = new RpcGovernanceConfig(
                Duration.ofMillis(800), 128, Duration.ofMillis(200), 128);
        PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

        try (ActorSystem actors = new ActorSystem(2, 32);
             NettyClusterTransport transport = new NettyClusterTransport(
                     endpoints::get, PayloadCodecRegistry.commonDefaults(), transportConfig);
             ClusterRpcGateway gateway = new ClusterRpcGateway(
                     local, directory, topology(), transport, true, rpcConfig);
             DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, gateway)) {
            ActorRef actor = actors.actor(role == ServiceKind.GAME ? "player-" + PLAYER_ID : "scene-process-agent");
            if (role == ServiceKind.SCENE) {
                bindSceneHandler(actors, actor, gateway, output);
            }
            emit(output, "CB_READY", role.name(), Long.toString(ProcessHandle.current().pid()));
            controlLoop(role, actors, actor, scene, messages, transport, gateway, output);
        }
    }

    private static void controlLoop(
            ServiceKind role,
            ActorSystem actors,
            ActorRef actor,
            ServiceDescriptor scene,
            DefaultAgentMessagePort messages,
            NettyClusterTransport transport,
            ClusterRpcGateway gateway,
            PrintWriter output
    ) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            String[] command = line.split("\\|", 3);
            if ("EXIT".equals(command[0])) {
                return;
            }
            if ("CALL".equals(command[0]) && role == ServiceKind.GAME && command.length == 3) {
                submitCall(actors, actor, scene, messages, command[1], command[2], output);
                continue;
            }
            if ("STATS".equals(command[0]) && command.length >= 2) {
                var network = transport.stats();
                emit(output,
                        "CB_STATS",
                        command[1],
                        Integer.toString(network.activeConnections()),
                        Long.toString(network.connectionAttempts()),
                        Long.toString(network.connectionFailures()),
                        Long.toString(gateway.stats().pendingRequests()),
                        Long.toString(network.inFlightWrites())
                );
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
            PrintWriter output
    ) {
        actors.send(player, ignored -> messages.callRemote(
                player,
                RpcRequest.toService(
                        scene.id(),
                        OPERATION,
                        requestId + ":" + payload,
                        String.class
                ),
                RemoteActorCallOptions.of(Duration.ofSeconds(2)),
                new RemoteActorCallback<>() {
                    @Override
                    public void success(ActorContext context, String response) {
                        emit(output,
                                "CB_RESULT",
                                requestId,
                                "SUCCESS",
                                response,
                                Thread.currentThread().getName(),
                                ActorContext.requireCurrent(player).self().id()
                        );
                    }

                    @Override
                    public void failure(
                            ActorContext context,
                            AgentDeliveryResult delivery,
                            Throwable error
                    ) {
                        emit(output,
                                "CB_RESULT",
                                requestId,
                                "FAILURE",
                                delivery.status().name(),
                                error.getClass().getSimpleName(),
                                Thread.currentThread().getName(),
                                ActorContext.requireCurrent(player).self().id()
                        );
                    }
                }
        ));
    }

    private static void bindSceneHandler(
            ActorSystem actors,
            ActorRef scene,
            ClusterRpcGateway gateway,
            PrintWriter output
    ) {
        gateway.handle(OPERATION, (request, responder) -> {
            String payload = (String) request.payload();
            boolean accepted = actors.trySend(scene, ignored -> {
                String requestId = payload.substring(0, payload.indexOf(':'));
                emit(output,
                        "CB_SCENE_HANDLED",
                        requestId,
                        Thread.currentThread().getName(),
                        ActorContext.requireCurrent(scene).self().id()
                );
                responder.success("echo:" + payload);
            });
            if (!accepted) {
                responder.failure(new IllegalStateException("scene actor mailbox rejected request"));
            }
        });
    }

    private static ClusterTopology topology() {
        return new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.SCENE)
                .allow(ServiceKind.SCENE, ServiceKind.GAME);
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(OPERATION),
                Map.of()
        );
    }

    private static void emit(PrintWriter output, String... fields) {
        synchronized (output) {
            output.println(String.join("|", fields));
        }
    }
}
