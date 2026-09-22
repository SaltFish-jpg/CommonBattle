package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.network.ForwardingProxy;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.RemoteServiceRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.actor.ActorSystem;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Proxy 服启动入口。
 */
public final class ProxyServerMain {
    private ProxyServerMain() {
    }

    public static void main(String[] args) throws Exception {
        BootRuntime runtime = new BootRuntime().installShutdownHook();
        try {
            ClusterNodeConfig config = ClusterNodeConfig.load(args, "cluster/proxy.properties");
            config.validate(ServiceKind.PROXY).throwIfInvalid();
            ServiceDescriptor local = ClusterDescriptors.fromConfig(config);
            ServiceDescriptor center = ClusterDescriptors.center(config);
            ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
            directory.seed(center);
            PayloadCodecRegistry codecs = BootPayloadCodecs.clusterServer();
            NettyClusterTransport transport = runtime.add("nettyTransport", new NettyClusterTransport(
                    new DirectoryEndpointView(directory, local, center),
                    codecs
            ));
            ForwardingProxy forwarder = new ForwardingProxy(transport);
            ClusterRpcGateway gateway = runtime.add(
                    "rpcGateway",
                    new ClusterRpcGateway(local, directory, ClusterTopology.defaultCrossServer(), transport, false)
            );
            transport.bind(local, envelope -> {
                if (envelope.target().equals(local.id())) {
                    gateway.onMessage(envelope);
                } else {
                    forwarder.onMessage(envelope);
                }
            });
            RemoteServiceRegistry registry = new RemoteServiceRegistry(
                    local.id(),
                    gateway,
                    directory,
                    config.registrySubscriptionLeaseTtl()
            );
            BootRegistryRecovery.configure(runtime, config, registry);
            ClusterNode node = runtime.add("clusterNode", new ClusterNode(registry, local, directory));
            ActorSystem actors = BootActors.configure(runtime, config, Clock.systemUTC());
            node.start(
                    List.of(ServiceKind.CENTER, ServiceKind.GAME, ServiceKind.CHAT, ServiceKind.SCENE, ServiceKind.REGION),
                    config.registryLeaseTtl(),
                    config.registryHeartbeatInterval()
            );
            runtime.add("opsHttp", BootOpsHttp.start(config, local, actors, directory, runtime.healthRegistry()));
            System.out.println("Proxy server started: " + local.id().wireName()
                    + ", ops=" + config.opsEndpoint().host() + ":" + config.opsEndpoint().port());
            new CountDownLatch(1).await();
        } catch (Exception e) {
            runtime.closeSuppressing(e);
            throw e;
        }
    }
}
