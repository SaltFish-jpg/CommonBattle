package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterNode;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.netty.NettyClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.registry.RegistryPayloadCodecs;
import com.commonbattle.cluster.registry.RemoteServiceRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.CrossPayloadCodecs;
import com.commonbattle.example.cross.EnterSceneRequest;
import com.commonbattle.example.cross.EnterSceneResult;
import com.commonbattle.example.cross.LeaveSceneRequest;
import com.commonbattle.example.cross.LeaveSceneResult;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.example.cross.scene.LargeSceneShardService;
import com.commonbattle.example.cross.scene.MultiSmallSceneService;
import com.commonbattle.example.cross.scene.ProfileAwareSceneService;
import com.commonbattle.example.cross.scene.SceneHostingMode;
import com.commonbattle.example.cross.scene.ScenePlacement;
import com.commonbattle.example.cross.scene.SceneServiceStrategy;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigEventReplayRepairer;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.GameConfigWarmupResult;
import com.commonbattle.game.config.GameConfigWarmupService;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.config.RemoteGameConfigRecoveryClient;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.ProfileInterestSubscription;
import com.commonbattle.game.profile.RemoteProfileSnapshotReader;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scene 服启动入口。
 */
public final class SceneServerMain {
    private SceneServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        ClusterNodeConfig config = ClusterNodeConfig.load(args, "cluster/scene-small.properties");
        config.validate(ServiceKind.SCENE).throwIfInvalid();
        ActorSystem actors = new ActorSystem(config.actorWorkers());
        SceneServiceStrategy sceneService = sceneService(config, actors);
        ServiceDescriptor local = sceneService.descriptor();
        ServiceDescriptor center = ClusterDescriptors.center(config);
        ClusterDirectory directory = new ClusterDirectory(new InMemoryServiceRegistry());
        directory.seed(center);
        PayloadCodecRegistry codecs = ClusterEventPayloadCodecs.registerTo(RegistryPayloadCodecs.registerTo(CrossPayloadCodecs.create()));
        NettyClusterTransport transport = new NettyClusterTransport(
                new DirectoryEndpointView(directory, local, center),
                codecs
        );
        ClusterRpcGateway gateway = new ClusterRpcGateway(local, directory, ClusterTopology.defaultCrossServer(), transport);
        ClusterVersionedEventBus eventBus = new ClusterVersionedEventBus(local.id(), gateway);
        LocalProfileCache sceneProfileCache = new LocalProfileCache();
        ExecutorService profileRepairExecutor = Executors.newFixedThreadPool(
                profileRepairWorkers(config),
                new NamedThreadFactory("common-battle-profile-repair")
        );
        ProfileInterestSubscription profileInterests = new ProfileInterestSubscription(
                eventBus,
                sceneProfileCache,
                new RemoteProfileSnapshotReader(gateway, Duration.ofSeconds(1)),
                profileRepairExecutor
        );
        SceneProfileAwarenessAgent profileAwareness = new SceneProfileAwarenessAgent(
                new DefaultAgentMessagePort(actors, gateway),
                actors.actor("scene-profile:" + local.id().node()),
                profileInterests,
                sceneProfileCache
        );
        SceneServiceStrategy activeSceneService = new ProfileAwareSceneService(sceneService, profileAwareness);
        gateway.handle(SceneOperations.ENTER, (request, responder) -> {
            EnterSceneRequest payload = (EnterSceneRequest) request.payload();
            ScenePlacement placement = activeSceneService.enter(payload.playerId(), payload.sceneId(), 0, 0);
            responder.success(new EnterSceneResult(payload.playerId(), placement.sceneId(), 0));
        });
        gateway.handle(SceneOperations.LEAVE, (request, responder) -> {
            LeaveSceneRequest payload = (LeaveSceneRequest) request.payload();
            boolean left = activeSceneService.leave(payload.playerId(), payload.sceneId());
            responder.success(new LeaveSceneResult(payload.playerId(), payload.sceneId(), left));
        });
        RemoteServiceRegistry registry = new RemoteServiceRegistry(local.id(), gateway, directory);
        ClusterNode node = new ClusterNode(registry, local, directory);
        node.start(
                List.of(ServiceKind.GAME, ServiceKind.PROXY, ServiceKind.REGION),
                config.registryLeaseTtl(),
                config.registryHeartbeatInterval()
        );
        LocalGameConfigCache configCache = new LocalGameConfigCache(
                new GameConfigValidator(),
                Clock.systemUTC()
        );
        ClusterEventSubscriptionManager eventSubscriptions = new ClusterEventSubscriptionManager(eventBus);
        RemoteGameConfigRecoveryClient configRecovery = new RemoteGameConfigRecoveryClient(gateway, configCache);
        GameConfigAutoRecovery configAutoRecovery = new GameConfigAutoRecovery(configRecovery);
        configCache.attachRecoveryTrigger(configAutoRecovery);
        eventSubscriptions.register(
                GameConfigChangedEvent.TOPIC,
                configCache,
                () -> java.util.Map.of(GameConfigChangedEvent.OWNER_KEY, configCache.appliedEventRevision()),
                new GameConfigEventReplayRepairer(configAutoRecovery, configCache::appliedEventRevision, configCache::stale)
        );
        eventSubscriptions.start();
        GameConfigWarmupResult warmup = new GameConfigWarmupService(
                configRecovery,
                Clock.systemUTC()
        ).warmup(config.configWarmupTimeout());
        if (!warmup.ready()) {
            throw new IllegalStateException("Scene config warmup failed: " + warmup.message());
        }
        BootOpsHttp.start(config, local, actors, directory, gateway, transport, node,
                List.of(configCache), List.of(configAutoRecovery), List.of(eventSubscriptions), List.of(profileInterests));
        System.out.println("Scene server started: " + local.id().wireName()
                + ", config=" + configCache.active().version()
                + ", ops=" + config.opsEndpoint().host() + ":" + config.opsEndpoint().port());
        new CountDownLatch(1).await();
    }

    private static SceneServiceStrategy sceneService(ClusterNodeConfig config, ActorSystem actors) {
        if (config.sceneMode() == SceneHostingMode.LARGE_SCENE_SHARD) {
            return LargeSceneShardService.create(
                    actors,
                    config.region(),
                    config.node(),
                    config.endpoint(),
                    config.sceneId(),
                    config.sceneShards()
            );
        }
        return MultiSmallSceneService.create(
                actors,
                config.region(),
                config.node(),
                config.endpoint(),
                config.sceneCapacity()
        );
    }

    private static int profileRepairWorkers(ClusterNodeConfig config) {
        return Math.max(1, Math.min(2, config.actorWorkers()));
    }

    private static final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private final AtomicInteger nextId = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
