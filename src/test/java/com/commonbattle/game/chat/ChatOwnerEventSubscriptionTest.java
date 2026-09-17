package com.commonbattle.game.chat;

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
import com.commonbattle.cluster.event.EventReplayRepairer;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.event.ActorMailboxEventSubscriber;
import com.commonbattle.game.event.OwnerActorEventSubscription;
import com.commonbattle.game.player.event.PlayerDomainEventProcessor;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.game.profile.ProfileOwnerEventInterests;
import com.commonbattle.game.profile.ProfileOwnerKeyParser;
import com.commonbattle.game.profile.ProfileRuntime;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import com.commonbattle.game.social.AllianceOwnerKeyParser;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendOwnerKeyParser;
import com.commonbattle.game.social.FriendRelationAction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatOwnerEventSubscriptionTest {
    @Test
    void chatInterestReceivesProfileFriendAndAllianceEventsThroughActorMailboxes() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            AtomicReference<ChatJoinResult> joined = new AtomicReference<>();

            fixture.manager().join(new ChatJoinRequest("world", 10001L, 900L), joined::set);
            fixture.executor().runAll();
            fixture.gameEvents().publish(profileEvent(10001L, 1, "Hero"));
            fixture.gameEvents().publish(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 1));
            fixture.gameEvents().publish(new AllianceMemberChangedEvent(900L, 10001L, AllianceMemberAction.JOIN, 1));
            fixture.executor().runAll();

            assertEquals(ChatJoinStatus.JOINED, joined.get().status());
            assertEquals("Hero", fixture.profiles().read(10001L,
                    com.commonbattle.game.profile.ProfileReadMode.LOCAL_FAST).profile().orElseThrow().snapshot().name());
            assertEquals(Set.of(20002L), fixture.friendAwareness().friendsOf(10001L).orElseThrow().friends());
            assertEquals(900L, fixture.allianceAwareness().allianceOf(10001L).orElseThrow().allianceId());
            assertEquals(1, fixture.profileInterests().stats().watchedOwners());
            assertEquals(1, fixture.friendInterests().stats().watchedOwners());
            assertEquals(1, fixture.allianceInterests().stats().watchedOwners());
        }
    }

    private static ProfileChangedEvent profileEvent(long playerId, long revision, String name) {
        return new ProfileChangedEvent(
                playerId,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        playerId,
                        name,
                        20,
                        new AppearanceSummary("avatar", "frame", "costume"),
                        AllianceBrief.none(),
                        new FriendBrief(1, 1),
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
            RecordingExecutor executor,
            ActorSystem actors,
            LocalClusterTransport transport,
            ClusterRpcGateway centerGateway,
            ClusterRpcGateway gameGateway,
            ClusterRpcGateway chatGateway,
            ClusterVersionedEventBus gameEvents,
            ProfileRuntime profiles,
            SceneFriendAwarenessAgent friendAwareness,
            SceneAllianceAwarenessAgent allianceAwareness,
            OwnerActorEventSubscription profileInterests,
            OwnerActorEventSubscription friendInterests,
            OwnerActorEventSubscription allianceInterests,
            ChatChannelManager manager
    ) implements AutoCloseable {
        private static Fixture create() {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
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
            ServiceDescriptor chat = descriptor(ServiceKind.CHAT, "chat-1", 9006, Set.of("chat.world.join"));
            ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
            registry.register(center);
            registry.register(game);
            registry.register(chat);
            registry.register(scene);
            ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, directory(registry), topology, transport);
            new ClusterEventCenter(center, transport, centerGateway);
            ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, directory(registry), topology, transport);
            ClusterRpcGateway chatGateway = new ClusterRpcGateway(chat, directory(registry), topology, transport);
            ClusterVersionedEventBus gameEvents = new ClusterVersionedEventBus(game.id(), gameGateway);
            ClusterVersionedEventBus chatEvents = new ClusterVersionedEventBus(chat.id(), chatGateway);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            LocalProfileCache profileCache = new LocalProfileCache();
            ProfileOwnerEventInterests profileOwnerInterests = new ProfileOwnerEventInterests();
            ProfileRuntime profiles = new ProfileRuntime(profileCache, profileOwnerInterests, ignored -> Optional.empty());
            ActorRef profileActor = actors.actor("chat-profile-awareness");
            ActorRef friendActor = actors.actor("chat-friend-awareness");
            ActorRef domainActor = actors.actor("chat-domain-awareness");
            ActorRef allianceActor = actors.actor("chat-alliance-awareness");
            SceneProfileAwarenessAgent profileAwareness = new SceneProfileAwarenessAgent(messages, profileActor, profiles);
            SceneFriendAwarenessAgent friendAwareness = new SceneFriendAwarenessAgent(messages, friendActor);
            ScenePlayerDomainEventAgent domainAwareness = new ScenePlayerDomainEventAgent(
                    messages,
                    domainActor,
                    new PlayerDomainEventProcessor()
            );
            SceneAllianceAwarenessAgent allianceAwareness = new SceneAllianceAwarenessAgent(messages, allianceActor);
            ActorMailboxEventSubscriber profileSubscriber = new ActorMailboxEventSubscriber(
                    messages,
                    profileActor,
                    (context, event) -> profileAwareness.handleProfileChanged((ProfileChangedEvent) event)
            );
            OwnerActorEventSubscription profileInterests = new OwnerActorEventSubscription(
                    chatEvents,
                    ProfileChangedEvent.TOPIC,
                    profileSubscriber,
                    ownerKey -> ProfileOwnerKeyParser.INSTANCE.parse(ownerKey)
                            .stream()
                            .map(profileCache::revisionOf)
                            .findFirst()
                            .orElse(0L),
                    EventReplayRepairer.noop()
            );
            profileOwnerInterests.attach(profileInterests, profileInterests);
            ActorMailboxEventSubscriber friendSubscriber = new ActorMailboxEventSubscriber(
                    messages,
                    friendActor,
                    (context, event) -> friendAwareness.handleFriendChanged((FriendChangedEvent) event)
            );
            OwnerActorEventSubscription friendInterests = new OwnerActorEventSubscription(
                    chatEvents,
                    FriendChangedEvent.TOPIC,
                    friendSubscriber,
                    ownerKey -> FriendOwnerKeyParser.INSTANCE.parse(ownerKey)
                            .stream()
                            .map(friendAwareness::revisionOf)
                            .findFirst()
                            .orElse(0L),
                    EventReplayRepairer.noop()
            );
            friendAwareness.attachInterests(friendInterests);
            ActorMailboxEventSubscriber allianceSubscriber = new ActorMailboxEventSubscriber(
                    messages,
                    allianceActor,
                    (context, event) -> allianceAwareness.handleAllianceChanged((AllianceMemberChangedEvent) event)
            );
            OwnerActorEventSubscription allianceInterests = new OwnerActorEventSubscription(
                    chatEvents,
                    AllianceMemberChangedEvent.TOPIC,
                    allianceSubscriber,
                    ownerKey -> AllianceOwnerKeyParser.INSTANCE.parse(ownerKey)
                            .stream()
                            .map(allianceAwareness::revisionOf)
                            .findFirst()
                            .orElse(0L),
                    EventReplayRepairer.noop()
            );
            allianceAwareness.attachInterests(allianceInterests);
            ScenePlayerInterestCoordinator interests = new ScenePlayerInterestCoordinator(
                    profileAwareness,
                    friendAwareness,
                    domainAwareness,
                    allianceAwareness
            );
            ChatChannelManager manager = new ChatChannelManager(
                    actors,
                    messages,
                    interests,
                    new ProfileAwareChatMessagePolicy(profiles),
                    ChatDeliverySink.noop(),
                    java.time.Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), java.time.ZoneOffset.UTC),
                    ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES
            );
            return new Fixture(
                    executor,
                    actors,
                    transport,
                    centerGateway,
                    gameGateway,
                    chatGateway,
                    gameEvents,
                    profiles,
                    friendAwareness,
                    allianceAwareness,
                    profileInterests,
                    friendInterests,
                    allianceInterests,
                    manager
            );
        }

        @Override
        public void close() throws Exception {
            profileInterests.close();
            friendInterests.close();
            allianceInterests.close();
            actors.close();
            centerGateway.close();
            gameGateway.close();
            chatGateway.close();
            transport.close();
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        private void runAll() {
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
