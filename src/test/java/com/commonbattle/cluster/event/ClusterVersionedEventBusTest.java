package com.commonbattle.cluster.event;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClusterVersionedEventBusTest {
    @Test
    void serviceSubscribesTopicAndReceivesPublishedProfileEvent() {
        Fixture fixture = fixture(ClusterEventCenter.DEFAULT_HISTORY_LIMIT);
        LocalProfileCache sceneCache = new LocalProfileCache();

        fixture.sceneEvents().subscribe(ProfileChangedEvent.TOPIC, event -> sceneCache.apply((ProfileChangedEvent) event));
        fixture.gameEvents().publish(profileEvent(1, "avatar_2"));

        assertEquals("avatar_2", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());
        assertEquals(1, sceneCache.revisionOf(10001L));
    }

    @Test
    void replayDeliversEventsPublishedBeforeSubscription() {
        Fixture fixture = fixture(ClusterEventCenter.DEFAULT_HISTORY_LIMIT);
        LocalProfileCache sceneCache = new LocalProfileCache();

        fixture.gameEvents().publish(profileEvent(1, "avatar_2"));
        fixture.gameEvents().publish(profileEvent(2, "avatar_3"));
        fixture.gameEvents().publish(profileEvent(20002L, 1, "avatar_other"));
        fixture.sceneEvents().subscribe(
                ProfileChangedEvent.TOPIC,
                event -> sceneCache.apply((ProfileChangedEvent) event),
                Map.of("profile:10001", 0L)
        );

        assertEquals(3, fixture.center().historySize(ProfileChangedEvent.TOPIC));
        assertEquals("avatar_3", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());
        assertEquals(2, sceneCache.revisionOf(10001L));
        assertEquals(0, sceneCache.revisionOf(20002L));
    }

    @Test
    void ownerFilteredSubscriptionReceivesOnlyMatchingProfileEvents() {
        Fixture fixture = fixture(ClusterEventCenter.DEFAULT_HISTORY_LIMIT);
        LocalProfileCache sceneCache = new LocalProfileCache();
        AtomicInteger delivered = new AtomicInteger();

        fixture.sceneEvents().subscribe(
                ProfileChangedEvent.TOPIC,
                event -> {
                    delivered.incrementAndGet();
                    sceneCache.apply((ProfileChangedEvent) event);
                },
                Set.of(ProfileChangedEvent.ownerKey(10001L))
        );
        fixture.gameEvents().publish(profileEvent(20002L, 1, "avatar_other"));
        fixture.gameEvents().publish(profileEvent(10001L, 1, "avatar_2"));

        assertEquals(1, delivered.get());
        assertEquals("avatar_2", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());
        assertEquals(0, sceneCache.revisionOf(20002L));
        assertEquals(Set.of(fixture.sceneId()), fixture.center().subscribers(ProfileChangedEvent.TOPIC));
    }

    @Test
    void replayStartsAfterKnownRevision() {
        Fixture fixture = fixture(ClusterEventCenter.DEFAULT_HISTORY_LIMIT);
        AtomicInteger delivered = new AtomicInteger();
        LocalProfileCache sceneCache = new LocalProfileCache();

        fixture.gameEvents().publish(profileEvent(1, "avatar_2"));
        fixture.gameEvents().publish(profileEvent(2, "avatar_3"));
        fixture.sceneEvents().subscribe(
                ProfileChangedEvent.TOPIC,
                event -> {
                    delivered.incrementAndGet();
                    sceneCache.apply((ProfileChangedEvent) event);
                },
                Map.of("profile:10001", 1L)
        );

        assertEquals(1, delivered.get());
        assertEquals("avatar_3", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());
        assertEquals(2, sceneCache.revisionOf(10001L));
    }

    @Test
    void replayReportsOwnersLostByHistoryWindow() {
        Fixture fixture = fixture(1);
        AtomicReference<EventReplayResult> result = new AtomicReference<>();

        fixture.gameEvents().publish(profileEvent(1, "avatar_2"));
        fixture.gameEvents().publish(profileEvent(2, "avatar_3"));
        fixture.sceneEvents().replay(ProfileChangedEvent.TOPIC, Map.of("profile:10001", 0L), new RpcCallback<>() {
            @Override
            public void success(EventReplayResult response) {
                result.set(response);
            }

            @Override
            public void failure(Throwable error) {
                throw new AssertionError(error);
            }
        });

        assertNotNull(result.get());
        assertEquals(1, result.get().delivered());
        assertEquals(1, result.get().unavailableOwners());
        assertEquals(Set.of("profile:10001"), result.get().unavailableOwnerKeys());
        assertEquals(1, fixture.center().stats().topics().get(ProfileChangedEvent.TOPIC).droppedEvents());
    }

    @Test
    void eventCenterUsesTopicSpecificHistoryLimit() {
        Fixture fixture = fixture(new ClusterEventHistoryPolicy(
                1,
                Map.of(ProfileChangedEvent.TOPIC, 2)
        ));

        fixture.gameEvents().publish(profileEvent(1, "avatar_1"));
        fixture.gameEvents().publish(profileEvent(2, "avatar_2"));
        fixture.gameEvents().publish(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1));
        fixture.gameEvents().publish(new AllianceMemberChangedEvent(100, 20002L, AllianceMemberAction.JOIN, 2));

        ClusterEventCenterStats stats = fixture.center().stats();
        assertEquals(2, stats.topics().get(ProfileChangedEvent.TOPIC).historyLimit());
        assertEquals(2, stats.topics().get(ProfileChangedEvent.TOPIC).retainedEvents());
        assertEquals(0, stats.topics().get(ProfileChangedEvent.TOPIC).droppedEvents());
        assertEquals(1, stats.topics().get(AllianceMemberChangedEvent.TOPIC).historyLimit());
        assertEquals(1, stats.topics().get(AllianceMemberChangedEvent.TOPIC).retainedEvents());
        assertEquals(1, stats.topics().get(AllianceMemberChangedEvent.TOPIC).droppedEvents());
    }

    @Test
    void multipleLocalTopicSubscribersShareOneRemoteSubscription() throws Exception {
        Fixture fixture = fixture(ClusterEventCenter.DEFAULT_HISTORY_LIMIT);
        AtomicInteger firstDelivered = new AtomicInteger();
        AtomicInteger secondDelivered = new AtomicInteger();

        AutoCloseable first = fixture.sceneEvents().subscribe(ProfileChangedEvent.TOPIC, event -> firstDelivered.incrementAndGet());
        AutoCloseable second = fixture.sceneEvents().subscribe(ProfileChangedEvent.TOPIC, event -> secondDelivered.incrementAndGet());
        first.close();
        fixture.gameEvents().publish(profileEvent(1, "avatar_2"));
        assertEquals(Set.of(fixture.sceneId()), fixture.center().subscribers(ProfileChangedEvent.TOPIC));
        second.close();
        fixture.gameEvents().publish(profileEvent(2, "avatar_3"));

        assertEquals(Set.of(), fixture.center().subscribers(ProfileChangedEvent.TOPIC));
        assertEquals(0, firstDelivered.get());
        assertEquals(1, secondDelivered.get());
    }

    @Test
    void expiredSubscriptionStopsFutureDelivery() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        Fixture fixture = fixture(ClusterEventHistoryPolicy.fixed(10), clock, Duration.ofMillis(100));
        AtomicInteger delivered = new AtomicInteger();

        fixture.sceneEvents().subscribe(ProfileChangedEvent.TOPIC, event -> delivered.incrementAndGet());
        clock.advance(Duration.ofMillis(101));
        assertEquals(1, fixture.center().expireSubscriptions(clock.instant()));
        fixture.gameEvents().publish(profileEvent(1, "avatar_2"));

        assertEquals(Set.of(), fixture.center().subscribers(ProfileChangedEvent.TOPIC));
        assertEquals(0, delivered.get());
        assertEquals(1, fixture.center().stats().topics().get(ProfileChangedEvent.TOPIC).expiredSubscriptions());
    }

    @Test
    void eventSubscriptionLeaseRenewerRefreshesActiveRemoteSubscriptions() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        Fixture fixture = fixture(ClusterEventHistoryPolicy.fixed(10), clock, Duration.ofMillis(100));
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try (ClusterEventSubscriptionLeaseRenewer renewer = new ClusterEventSubscriptionLeaseRenewer(
                fixture.sceneEvents(),
                Duration.ofSeconds(1),
                executor
        )) {
            fixture.sceneEvents().subscribe(ProfileChangedEvent.TOPIC, event -> {
            });

            clock.advance(Duration.ofMillis(80));
            assertEquals(1, renewer.renewOnce());
            clock.advance(Duration.ofMillis(80));

            assertEquals(0, fixture.center().expireSubscriptions(clock.instant()));
            assertEquals(1, renewer.runs());
            assertEquals(1, renewer.renewedSubscriptions());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void eventCenterRecordsDeliveryFailuresWithoutBreakingPublisher() {
        Fixture fixture = fixture(ClusterEventCenter.DEFAULT_HISTORY_LIMIT);
        fixture.sceneEvents().subscribe(ProfileChangedEvent.TOPIC, event -> {
        });
        fixture.transport().close();

        fixture.center().publishLocal(profileEvent(1, "avatar_2"));

        ClusterEventTopicStats stats = fixture.center().stats().topics().get(ProfileChangedEvent.TOPIC);
        assertEquals(1, stats.publishedEvents());
        assertEquals(1, stats.deliveryFailures());
    }

    private static Fixture fixture(int historyLimit) {
        return fixture(ClusterEventHistoryPolicy.fixed(historyLimit));
    }

    private static Fixture fixture(ClusterEventHistoryPolicy historyPolicy) {
        return fixture(historyPolicy, Clock.systemUTC(), Duration.ofSeconds(15));
    }

    private static Fixture fixture(ClusterEventHistoryPolicy historyPolicy, Clock clock, Duration subscriptionLeaseTtl) {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000,
                Set.of(
                        ClusterEventOperations.SUBSCRIBE,
                        ClusterEventOperations.UNSUBSCRIBE,
                        ClusterEventOperations.PUBLISH,
                        ClusterEventOperations.REPLAY
                ));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        registry.register(center);
        registry.register(game);
        registry.register(scene);

        ClusterDirectory centerDirectory = directory(registry);
        ClusterDirectory gameDirectory = directory(registry);
        ClusterDirectory sceneDirectory = directory(registry);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerDirectory, topology, transport);
        ClusterEventCenter centerEvents = new ClusterEventCenter(
                center,
                transport,
                centerGateway,
                historyPolicy,
                clock,
                subscriptionLeaseTtl
        );
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
        ClusterVersionedEventBus gameEvents = new ClusterVersionedEventBus(game.id(), gameGateway, subscriptionLeaseTtl);
        ClusterVersionedEventBus sceneEvents = new ClusterVersionedEventBus(scene.id(), sceneGateway, subscriptionLeaseTtl);
        return new Fixture(centerEvents, gameEvents, sceneEvents, scene.id(), transport);
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
        return profileEvent(10001L, revision, avatar);
    }

    private static ProfileChangedEvent profileEvent(long playerId, long revision, String avatar) {
        return new ProfileChangedEvent(
                playerId,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        playerId,
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

    private record Fixture(
            ClusterEventCenter center,
            ClusterVersionedEventBus gameEvents,
            ClusterVersionedEventBus sceneEvents,
            ServiceId sceneId,
            LocalClusterTransport transport
    ) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
