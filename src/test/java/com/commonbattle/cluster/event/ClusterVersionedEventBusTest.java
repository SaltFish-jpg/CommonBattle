package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClusterVersionedEventBusTest {
    @Test
    void serviceSubscribesTopicAndReceivesPublishedProfileEvent() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000,
                Set.of(ClusterEventOperations.SUBSCRIBE, ClusterEventOperations.UNSUBSCRIBE, ClusterEventOperations.PUBLISH));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of("scene.enter"));
        registry.register(center);
        registry.register(game);
        registry.register(scene);

        ClusterDirectory centerDirectory = directory(registry);
        ClusterDirectory gameDirectory = directory(registry);
        ClusterDirectory sceneDirectory = directory(registry);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerDirectory, topology, transport);
        new ClusterEventCenter(center, transport, centerGateway);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
        ClusterVersionedEventBus gameEvents = new ClusterVersionedEventBus(game.id(), gameGateway);
        ClusterVersionedEventBus sceneEvents = new ClusterVersionedEventBus(scene.id(), sceneGateway);
        LocalProfileCache sceneCache = new LocalProfileCache();

        sceneEvents.subscribe(ProfileChangedEvent.TOPIC, event -> sceneCache.apply((ProfileChangedEvent) event));
        gameEvents.publish(profileEvent(1, "avatar_2"));

        assertEquals("avatar_2", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());
        assertEquals(1, sceneCache.revisionOf(10001L));
    }

    private static ClusterDirectory directory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        return directory;
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
                Map.of()
        );
    }

    private static ProfileChangedEvent profileEvent(long revision, String avatar) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        20,
                        new AppearanceSummary(avatar, "frame_1", "costume_1"),
                        new AllianceBrief(100, "alliance", "badge"),
                        new FriendBrief(3, 1),
                        revision,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
    }
}
