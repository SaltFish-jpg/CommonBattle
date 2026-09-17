package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.backpressure.ActorMailboxPressureAdmissionController;
import com.commonbattle.actor.backpressure.ActorMailboxPressurePolicy;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.player.event.PlayerDomainEventProcessor;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.profile.ProfileRuntime;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;
import com.commonbattle.game.scene.ScenePlayerInterestStats;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatChannelAgentTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void joinAndLeaveDriveVisibleDataInterestsThroughMailbox() {
        Fixture fixture = Fixture.create();
        AtomicReference<ChatJoinResult> joined = new AtomicReference<>();
        AtomicReference<ChatLeaveResult> left = new AtomicReference<>();

        fixture.manager.join(new ChatJoinRequest("world", 10001L, 900L), joined::set);

        assertEquals(null, joined.get());
        fixture.executor.runAll();

        assertEquals(ChatJoinStatus.JOINED, joined.get().status());
        assertEquals(new ScenePlayerInterestStats(1, 1, 1, 1, 0, 0, 0, 0),
                fixture.interests.interestStats());
        assertEquals(List.of(10001L), fixture.profileInterests.watched);

        fixture.manager.leave(new ChatLeaveRequest("world", 10001L), left::set);
        fixture.executor.runAll();

        assertEquals(ChatLeaveStatus.LEFT, left.get().status());
        assertEquals(new ScenePlayerInterestStats(0, 0, 0, 1, 0, 1, 0, 0),
                fixture.interests.interestStats());
        assertEquals(List.of(10001L), fixture.profileInterests.unwatched);
    }

    @Test
    void sendRequiresMemberAndFreshProfileSnapshot() {
        Fixture fixture = Fixture.create();
        fixture.profiles.refresh(snapshot(10001L, "Hero", 3));
        AtomicReference<ChatSendResult> response = new AtomicReference<>();

        fixture.manager.join(new ChatJoinRequest("world", 10001L, 0), ignored -> {
        });
        fixture.manager.send(new ChatSendRequest("world", 10001L, "  hello  ", 3), response::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.SENT, response.get().status());
        ChatDelivery delivery = response.get().delivery().orElseThrow();
        assertEquals("Hero", delivery.senderName());
        assertEquals("hello", delivery.text());
        assertEquals(1, delivery.revision());
        assertEquals(CLOCK.instant(), delivery.sentAt());
        assertEquals(List.of(delivery), fixture.manager.channel("world").snapshotNow().history());
    }

    @Test
    void staleProfileRejectsSendWithoutAppendingHistory() {
        Fixture fixture = Fixture.create();
        fixture.profiles.refresh(snapshot(10001L, "OldName", 1));
        AtomicReference<ChatSendResult> response = new AtomicReference<>();

        fixture.manager.join(new ChatJoinRequest("world", 10001L, 0), ignored -> {
        });
        fixture.manager.send(new ChatSendRequest("world", 10001L, "hello", 5), response::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.STALE_PROFILE, response.get().status());
        assertEquals(Optional.empty(), response.get().delivery());
        assertEquals(List.of(), fixture.manager.channel("world").snapshotNow().history());
    }

    @Test
    void nonMemberCannotSend() {
        Fixture fixture = Fixture.create();
        AtomicReference<ChatSendResult> response = new AtomicReference<>();

        fixture.manager.send(new ChatSendRequest("world", 10001L, "hello", 0), response::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.NOT_IN_CHANNEL, response.get().status());
        assertEquals(List.of(), fixture.manager.channel("world").snapshotNow().history());
    }

    @Test
    void mailboxPressureRejectsBeforeChannelActorTaskIsQueued() {
        Fixture fixture = Fixture.create(new ActorMailboxPressurePolicy(true, 1, 0, Duration.ofMillis(50)));
        fixture.actors.send(fixture.actors.actor(ChatActorIds.channelActorId("world")), ignored -> {
        });
        AtomicReference<ChatJoinResult> joined = new AtomicReference<>();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.manager.join(new ChatJoinRequest("world", 10001L, 0), joined::set);
        fixture.manager.send(new ChatSendRequest("world", 10001L, "hello", 0), sent::set);

        assertEquals(ChatJoinStatus.BACKPRESSURED, joined.get().status());
        assertEquals(ChatSendStatus.BACKPRESSURED, sent.get().status());
        assertEquals(2, fixture.pressure.mailboxPressureStats().pressureRejected());
        assertEquals(1, fixture.executor.queued());
    }

    private static PlayerProfileSnapshot snapshot(long playerId, String name, long revision) {
        return new PlayerProfileSnapshot(
                playerId,
                name,
                20,
                new AppearanceSummary("avatar", "frame", "costume"),
                AllianceBrief.none(),
                new FriendBrief(3, 1),
                revision,
                CLOCK.instant()
        );
    }

    private record Fixture(
            RecordingExecutor executor,
            ActorSystem actors,
            ProfileRuntime profiles,
            RecordingProfileInterests profileInterests,
            ScenePlayerInterestCoordinator interests,
            ActorMailboxPressureAdmissionController pressure,
            ChatChannelManager manager
    ) {
        private static Fixture create() {
            return create(ActorMailboxPressurePolicy.disabled());
        }

        private static Fixture create(ActorMailboxPressurePolicy pressurePolicy) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            RecordingProfileInterests profileInterests = new RecordingProfileInterests();
            RecordingOwnerInterests friendInterests = new RecordingOwnerInterests();
            RecordingOwnerInterests domainInterests = new RecordingOwnerInterests();
            RecordingOwnerInterests allianceInterests = new RecordingOwnerInterests();
            ProfileRuntime profiles = new ProfileRuntime(
                    new LocalProfileCache(),
                    profileInterests,
                    ignored -> Optional.empty()
            );
            ScenePlayerInterestCoordinator interests = new ScenePlayerInterestCoordinator(
                    new SceneProfileAwarenessAgent(messages, actors.actor("chat-profile-awareness"), profiles),
                    new SceneFriendAwarenessAgent(messages, actors.actor("chat-friend-awareness"), friendInterests),
                    new ScenePlayerDomainEventAgent(
                            messages,
                            actors.actor("chat-domain-awareness"),
                            new PlayerDomainEventProcessor(),
                            domainInterests
                    ),
                    new SceneAllianceAwarenessAgent(messages, actors.actor("chat-alliance-awareness"), allianceInterests)
            );
            ActorMailboxPressureAdmissionController pressure = new ActorMailboxPressureAdmissionController(
                    (target, operation) -> AdmissionDecision.accept(),
                    actors,
                    pressurePolicy,
                    ChatActorIds::actorIdOf
            );
            return new Fixture(
                    executor,
                    actors,
                    profiles,
                    profileInterests,
                    interests,
                    pressure,
                    new ChatChannelManager(
                            actors,
                            messages,
                            interests,
                            new ProfileAwareChatMessagePolicy(profiles),
                            ChatDeliverySink.noop(),
                            CLOCK,
                            ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES,
                            pressure
                    )
            );
        }
    }

    private static final class RecordingProfileInterests implements ProfileInterestControl {
        private final List<Long> watched = new ArrayList<>();
        private final List<Long> unwatched = new ArrayList<>();

        @Override
        public void watch(long playerId) {
            watched.add(playerId);
        }

        @Override
        public void unwatch(long playerId) {
            unwatched.add(playerId);
        }
    }

    private static final class RecordingOwnerInterests implements OwnerEventInterestControl {
        @Override
        public void watchOwner(String ownerKey) {
        }

        @Override
        public void unwatchOwner(String ownerKey) {
        }

        @Override
        public void requestRepairOwners(Collection<String> ownerKeys) {
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

        private int queued() {
            return commands.size();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
