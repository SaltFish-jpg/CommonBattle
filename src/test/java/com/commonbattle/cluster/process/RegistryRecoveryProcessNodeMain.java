package com.commonbattle.cluster.process;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.RegistryEvent;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.netty.NettyClusterTransportConfig;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.CenterRegistryEndpoint;
import com.commonbattle.cluster.registry.RegistryOperations;
import com.commonbattle.cluster.registry.RemoteRegistryRecoveryScheduler;
import com.commonbattle.cluster.registry.RemoteServiceRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcGovernanceConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注册中心重启演练使用的独立 JVM 节点。
 * CENTER 持有易失注册表，WATCHER 验证订阅恢复，SCENE 验证租约续约后的自动重新注册。
 */
public final class RegistryRecoveryProcessNodeMain {
    private RegistryRecoveryProcessNodeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: <CENTER|WATCHER|SCENE> <centerPort> <localPort>");
        }
        String role = args[0];
        int centerPort = Integer.parseInt(args[1]);
        int localPort = Integer.parseInt(args[2]);
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-recovery", centerPort, centerTopics());
        if ("CENTER".equals(role)) {
            runCenter(center);
            return;
        }
        ServiceKind kind = "WATCHER".equals(role) ? ServiceKind.GAME : ServiceKind.SCENE;
        ServiceDescriptor local = descriptor(kind, role.toLowerCase() + "-recovery", localPort,
                Set.of(role.toLowerCase() + ".heartbeat"));
        runRemote(role, center, local);
    }

    private static void runCenter(ServiceDescriptor center) throws Exception {
        InMemoryServiceRegistry storage = new InMemoryServiceRegistry();
        storage.register(center);
        ClusterDirectory directory = new ClusterDirectory(storage);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        PrintWriter output = output();
        try (NettyClusterTransport transport = new NettyClusterTransport(
                directoryView(directory, center, center),
                registryCodecs(),
                transportConfig());
             ClusterRpcGateway gateway = new ClusterRpcGateway(
                     center, directory, ClusterTopology.defaultCrossServer(), transport, true, rpcConfig());
             CenterRegistryEndpoint endpoint = new CenterRegistryEndpoint(
                     center, storage, transport, gateway, java.time.Clock.systemUTC(), Duration.ofSeconds(3))) {
            emit(output, "CB_READY", "CENTER", Long.toString(ProcessHandle.current().pid()));
            centerControlLoop(storage, endpoint, output);
        } finally {
            directory.close();
        }
    }

    private static void runRemote(
            String role,
            ServiceDescriptor center,
            ServiceDescriptor local
    ) throws Exception {
        InMemoryServiceRegistry mirror = new InMemoryServiceRegistry();
        ClusterDirectory directory = new ClusterDirectory(mirror);
        directory.seed(center);
        PrintWriter output = output();
        try (NettyClusterTransport transport = new NettyClusterTransport(
                directoryView(directory, local, center),
                registryCodecs(),
                transportConfig());
             ClusterRpcGateway gateway = new ClusterRpcGateway(
                     local, directory, ClusterTopology.defaultCrossServer(), transport, true, rpcConfig())) {
            RemoteServiceRegistry registry = new RemoteServiceRegistry(
                    local.id(), gateway, directory, Duration.ofSeconds(3));
            ClusterNode node = new ClusterNode(registry, local, directory);
            RemoteRegistryRecoveryScheduler recovery = null;
            AutoCloseable eventSubscription = null;
            try {
                Duration heartbeat = "WATCHER".equals(role) ? Duration.ofMillis(100) : Duration.ofSeconds(5);
                node.start(
                        "WATCHER".equals(role) ? List.of(ServiceKind.SCENE) : List.of(),
                        Duration.ofSeconds(15),
                        heartbeat
                );
                if ("WATCHER".equals(role)) {
                    eventSubscription = registry.subscribe(
                            ServiceKind.SCENE,
                            event -> emitRegistryEvent(output, event)
                    );
                    recovery = new RemoteRegistryRecoveryScheduler(registry, Duration.ofMillis(100));
                    recovery.start();
                }
                emit(output, "CB_READY", role, Long.toString(ProcessHandle.current().pid()));
                remoteControlLoop(role, directory, node, recovery, output);
            } finally {
                if (recovery != null) {
                    recovery.close();
                }
                if (eventSubscription != null) {
                    eventSubscription.close();
                }
                node.close();
            }
        }
    }

    private static void centerControlLoop(
            InMemoryServiceRegistry storage,
            CenterRegistryEndpoint endpoint,
            PrintWriter output
    ) throws Exception {
        BufferedReader input = input();
        String line;
        while ((line = input.readLine()) != null) {
            String[] command = line.split("\\|", 2);
            if ("EXIT".equals(command[0])) {
                return;
            }
            if ("STATS".equals(command[0]) && command.length == 2) {
                var subscriptions = endpoint.stats();
                emit(output,
                        "CB_CENTER_STATS",
                        command[1],
                        Integer.toString(storage.list(ServiceKind.GAME).size()),
                        Integer.toString(storage.list(ServiceKind.SCENE).size()),
                        Integer.toString(subscriptions.subscribers()),
                        Integer.toString(subscriptions.references())
                );
            }
        }
    }

    private static void remoteControlLoop(
            String role,
            ClusterDirectory directory,
            ClusterNode node,
            RemoteRegistryRecoveryScheduler recovery,
            PrintWriter output
    ) throws Exception {
        BufferedReader input = input();
        String line;
        while ((line = input.readLine()) != null) {
            String[] command = line.split("\\|", 2);
            if ("EXIT".equals(command[0])) {
                return;
            }
            if ("STATS".equals(command[0]) && command.length == 2) {
                var lease = node.leaseRenewer().orElseThrow().stats();
                if (recovery == null) {
                    emit(output,
                            "CB_REGISTRY_STATS",
                            command[1],
                            role,
                            "0",
                            "0",
                            "0",
                            "0",
                            "0",
                            "0",
                            Long.toString(lease.failedRenewals()),
                            Long.toString(lease.reRegistrations())
                    );
                } else {
                    var stats = recovery.stats();
                    emit(output,
                            "CB_REGISTRY_STATS",
                            command[1],
                            role,
                            Integer.toString(directory.list(ServiceKind.SCENE).size()),
                            Long.toString(directory.version(ServiceKind.SCENE)),
                            Long.toString(stats.runs()),
                            Long.toString(stats.succeededRuns()),
                            Long.toString(stats.failedRuns()),
                            Long.toString(stats.recoveredKinds()),
                            Long.toString(lease.failedRenewals()),
                            Long.toString(lease.reRegistrations())
                    );
                }
            }
        }
    }

    private static void emitRegistryEvent(PrintWriter output, RegistryEvent event) {
        emit(output,
                "CB_REGISTRY_EVENT",
                event.type().name(),
                event.service().id().wireName(),
                Long.toString(event.version())
        );
    }

    private static NettyClusterTransport.ServiceRegistryView directoryView(
            ClusterDirectory directory,
            ServiceDescriptor local,
            ServiceDescriptor center
    ) {
        return serviceId -> {
            if (serviceId.equals(local.id())) {
                return local.endpoint();
            }
            if (serviceId.equals(center.id())) {
                return center.endpoint();
            }
            return directory.find(serviceId)
                    .map(ServiceDescriptor::endpoint)
                    .orElseThrow(() -> new IllegalStateException("No endpoint for " + serviceId.wireName()));
        };
    }

    private static PayloadCodecRegistry registryCodecs() {
        return com.commonbattle.cluster.registry.RegistryPayloadCodecs.registerTo(
                PayloadCodecRegistry.commonDefaults());
    }

    private static NettyClusterTransportConfig transportConfig() {
        return new NettyClusterTransportConfig(Duration.ofMillis(500), Duration.ofMillis(50), 64);
    }

    private static RpcGovernanceConfig rpcConfig() {
        return new RpcGovernanceConfig(Duration.ofMillis(800), 128, Duration.ofMillis(200), 128);
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

    private static Set<String> centerTopics() {
        return Set.of(
                RegistryOperations.REGISTER,
                RegistryOperations.HEARTBEAT,
                RegistryOperations.UNREGISTER,
                RegistryOperations.LIST,
                RegistryOperations.SUBSCRIBE,
                RegistryOperations.UNSUBSCRIBE,
                RegistryOperations.REPLAY
        );
    }

    private static BufferedReader input() {
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    private static PrintWriter output() {
        return new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    }

    private static void emit(PrintWriter output, String... fields) {
        synchronized (output) {
            output.println(String.join("|", fields));
        }
    }
}
