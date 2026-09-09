package com.commonbattle.game.event;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventCenter;
import com.commonbattle.cluster.event.ClusterEventOperations;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
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
import com.commonbattle.game.profile.ProfileOwnerEventInterests;
import com.commonbattle.game.profile.ProfileRuntime;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerActorEventSubscriptionTest {
    @Test
    void watchOwnerDeliversOnlyInterestedPlayerDomainEventsToActorMailbox() throws Exception {
        try (Fixture fixture = Fixture.create(ClusterEventCenter.DEFAULT_HISTORY_LIMIT)) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            List<Long> handledPlayers = new ArrayList<>();
            ActorMailboxEventSubscriber mailboxSubscriber = new ActorMailboxEventSubscriber(
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    actors.actor("scene-domain-events"),
                    (context, event) -> handledPlayers.add(((PlayerDomainVersionedEvent) event).playerId())
            );
            AtomicLong revision = new AtomicLong();
            try (OwnerActorEventSubscription subscription = new OwnerActorEventSubscription(
                    fixture.sceneEvents(),
                    PlayerDomainVersionedEvent.TOPIC,
                    mailboxSubscriber,
                    ownerKey -> revision.get(),
                    (topic, ownerKeys) -> {
                    }
            )) {
                subscription.watchOwner(PlayerDomainVersionedEvent.ownerKey(10001L));
                fixture.gameEvents().publish(playerEvent(20002L, 1));
                fixture.gameEvents().publish(playerEvent(10001L, 1));

                assertTrue(handledPlayers.isEmpty());
                executor.runAll();

                assertEquals(List.of(10001L), handledPlayers);
                assertEquals(1, mailboxSubscriber.stats().handledEvents());
                assertEquals(1, subscription.stats().watchedOwners());
                assertEquals(1, subscription.stats().watchReferences());
                assertEquals(1, subscription.stats().subscribeRequests());
                assertEquals(1, subscription.stats().replayAttempts());
            }
        }
    }

    @Test
    void duplicateWatchUsesReferenceCountingBeforeRemoteUnsubscribe() throws Exception {
        try (Fixture fixture = Fixture.create(ClusterEventCenter.DEFAULT_HISTORY_LIMIT)) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            List<Long> handledAlliances = new ArrayList<>();
            ActorMailboxEventSubscriber mailboxSubscriber = new ActorMailboxEventSubscriber(
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    actors.actor("scene-alliance-events"),
                    (context, event) -> handledAlliances.add(((AllianceMemberChangedEvent) event).allianceId())
            );
            try (OwnerActorEventSubscription subscription = new OwnerActorEventSubscription(
                    fixture.sceneEvents(),
                    AllianceMemberChangedEvent.TOPIC,
                    mailboxSubscriber,
                    ownerKey -> 0,
                    (topic, ownerKeys) -> {
                    }
            )) {
                subscription.watchOwner("alliance:100");
                subscription.watchOwner("alliance:100");
                subscription.unwatchOwner("alliance:100");
                fixture.gameEvents().publish(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 1));
                executor.runAll();

                assertEquals(List.of(100L), handledAlliances);
                assertTrue(subscription.watching("alliance:100"));
                assertEquals(1, subscription.stats().watchedOwners());
                assertEquals(1, subscription.stats().watchReferences());

                subscription.unwatchOwner("alliance:100");
                fixture.gameEvents().publish(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.LEAVE, 2));
                executor.runAll();

                assertFalse(subscription.watching("alliance:100"));
                assertEquals(List.of(100L), handledAlliances);
                assertEquals(1, subscription.stats().unsubscribeRequests());
            }
        }
    }

    @Test
    void replayGapTriggersSnapshotRepairForUnavailableOwners() throws Exception {
        try (Fixture fixture = Fixture.create(1)) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            List<Set<String>> repairs = new ArrayList<>();
            ActorMailboxEventSubscriber mailboxSubscriber = new ActorMailboxEventSubscriber(
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    actors.actor("scene-domain-events"),
                    (context, event) -> {
                    }
            );
            fixture.gameEvents().publish(playerEvent(10001L, 2));
            fixture.gameEvents().publish(playerEvent(10001L, 3));

            try (OwnerActorEventSubscription subscription = new OwnerActorEventSubscription(
                    fixture.sceneEvents(),
                    PlayerDomainVersionedEvent.TOPIC,
                    mailboxSubscriber,
                    ownerKey -> 1,
                    (topic, ownerKeys) -> repairs.add(Set.copyOf(ownerKeys))
            )) {
                subscription.watchOwner(PlayerDomainVersionedEvent.ownerKey(10001L));
                executor.runAll();

                assertEquals(List.of(Set.of(PlayerDomainVersionedEvent.ownerKey(10001L))), repairs);
                assertEquals(1, subscription.stats().repairRequests());
                assertEquals(1, subscription.stats().repairOwnerCount());
                assertEquals(0, subscription.stats().repairFailures());
            }
        }
    }

    @Test
    void explicitRepairRequestOnlyRepairsWatchedOwners() throws Exception {
        try (Fixture fixture = Fixture.create(ClusterEventCenter.DEFAULT_HISTORY_LIMIT)) {
            ActorSystem actors = new ActorSystem(Runnable::run, 64);
            List<Set<String>> repairs = new ArrayList<>();
            ActorMailboxEventSubscriber mailboxSubscriber = new ActorMailboxEventSubscriber(
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    actors.actor("scene-domain-events"),
                    (context, event) -> {
                    }
            );
            try (OwnerActorEventSubscription subscription = new OwnerActorEventSubscription(
                    fixture.sceneEvents(),
                    PlayerDomainVersionedEvent.TOPIC,
                    mailboxSubscriber,
                    ownerKey -> 0,
                    (topic, ownerKeys) -> repairs.add(Set.copyOf(ownerKeys))
            )) {
                String watchedOwner = PlayerDomainVersionedEvent.ownerKey(10001L);
                String unwatchedOwner = PlayerDomainVersionedEvent.ownerKey(20002L);
                subscription.watchOwner(watchedOwner);

                subscription.requestRepairOwners(Set.of(watchedOwner, unwatchedOwner));

                assertEquals(List.of(Set.of(watchedOwner)), repairs);
                assertEquals(1, subscription.stats().repairRequests());
                assertEquals(1, subscription.stats().repairOwnerCount());
            }
        }
    }

    @Test
    void profileOwnerInterestDeliversProfileChangesThroughActorMailbox() throws Exception {
        try (Fixture fixture = Fixture.create(ClusterEventCenter.DEFAULT_HISTORY_LIMIT)) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            LocalProfileCache cache = new LocalProfileCache();
            ProfileOwnerEventInterests interests = new ProfileOwnerEventInterests();
            ProfileRuntime profiles = new ProfileRuntime(cache, interests, ignored -> java.util.Optional.empty());
            ActorRef profileActor = actors.actor("scene-profile");
            SceneProfileAwarenessAgent scene = new SceneProfileAwarenessAgent(
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    profileActor,
                    profiles
            );
            ActorMailboxEventSubscriber mailboxSubscriber = new ActorMailboxEventSubscriber(
                    new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                    profileActor,
                    (context, event) -> scene.handleProfileChanged((ProfileChangedEvent) event)
            );
            try (OwnerActorEventSubscription subscription = new OwnerActorEventSubscription(
                    fixture.sceneEvents(),
                    ProfileChangedEvent.TOPIC,
                    mailboxSubscriber,
                    ownerKey -> cache.revisionOf(10001L),
                    (topic, ownerKeys) -> {
                    }
            )) {
                interests.attach(subscription, subscription);
                scene.enter(10001L);
                executor.runAll();

                fixture.gameEvents().publish(profileEvent(10001L, 1, "avatar_1"));

                assertTrue(scene.profileOf(10001L).isEmpty());
                executor.runAll();

                assertEquals("avatar_1",
                        scene.profileOf(10001L).orElseThrow().snapshot().appearance().avatar());
                assertEquals(1, interests.stats().watchedOwners());
                assertEquals(1, mailboxSubscriber.stats().handledEvents());
            }
        }
    }

    private static PlayerDomainVersionedEvent playerEvent(long playerId, long revision) {
        return new PlayerDomainVersionedEvent(
                playerId,
                BattleStageClearedEvent.TYPE,
                "forest-1",
                1,
                revision,
                Instant.parse("2026-09-01T00:00:00Z")
        );
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
                        AllianceBrief.none(),
                        new FriendBrief(3, 1),
                        revision,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
    }

    private static ClusterDirectory directory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        return directory;
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> operations) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                operations,
                Map.of()
        );
    }

    private record Fixture(
            LocalClusterTransport transport,
            ClusterRpcGateway centerGateway,
            ClusterRpcGateway gameGateway,
            ClusterRpcGateway sceneGateway,
            ClusterVersionedEventBus gameEvents,
            ClusterVersionedEventBus sceneEvents
    ) implements AutoCloseable {
        private static Fixture create(int historyLimit) {
            LocalClusterTransport transport = new LocalClusterTransport();
            ClusterTopology topology = ClusterTopology.defaultCrossServer();
            InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
            ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
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
            ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, directory(registry), topology, transport);
            new ClusterEventCenter(center, transport, centerGateway, historyLimit);
            ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, directory(registry), topology, transport);
            ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, directory(registry), topology, transport);
            return new Fixture(
                    transport,
                    centerGateway,
                    gameGateway,
                    sceneGateway,
                    new ClusterVersionedEventBus(game.id(), gameGateway),
                    new ClusterVersionedEventBus(scene.id(), sceneGateway)
            );
        }

        @Override
        public void close() {
            centerGateway.close();
            gameGateway.close();
            sceneGateway.close();
            transport.close();
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runAll() {
            while (!commands.isEmpty()) {
                commands.removeFirst().run();
            }
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
