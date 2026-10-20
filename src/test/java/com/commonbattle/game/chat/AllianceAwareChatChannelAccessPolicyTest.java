package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.player.event.PlayerDomainEventProcessor;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.game.social.AllianceMemberAction;
import com.commonbattle.game.social.AllianceMemberChangedEvent;
import com.commonbattle.game.social.AllianceSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllianceAwareChatChannelAccessPolicyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void trustedAllianceSnapshotAllowsJoinAndSend() {
        Fixture fixture = Fixture.create();
        fixture.refreshAlliance(900L, 1, Set.of(10001L));
        AtomicReference<ChatJoinResult> joined = new AtomicReference<>();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L), joined::set);
        fixture.channels.send(new ChatSendRequest(ChatChannelIds.alliance(900L), 10001L, "alliance", 0), sent::set);
        fixture.executor.runAll();

        assertEquals(ChatJoinStatus.JOINED, joined.get().status());
        assertEquals(ChatSendStatus.SENT, sent.get().status());
        assertEquals(List.of("alliance"),
                fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().history().stream()
                        .map(ChatDelivery::text)
                        .toList());
    }

    @Test
    void missingAllianceSnapshotRejectsJoinWithoutChangingMembers() {
        Fixture fixture = Fixture.create();
        AtomicReference<ChatJoinResult> joined = new AtomicReference<>();

        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L), joined::set);
        fixture.executor.runAll();

        assertEquals(ChatJoinStatus.STALE_ALLIANCE, joined.get().status());
        assertEquals(Set.of(), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members());
    }

    @Test
    void nonMemberAllianceSnapshotRejectsJoin() {
        Fixture fixture = Fixture.create();
        fixture.refreshAlliance(900L, 1, Set.of(20002L));
        AtomicReference<ChatJoinResult> joined = new AtomicReference<>();

        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L), joined::set);
        fixture.executor.runAll();

        assertEquals(ChatJoinStatus.NOT_ALLIANCE_MEMBER, joined.get().status());
        assertEquals(Set.of(), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members());
    }

    @Test
    void staleAllianceProjectionRejectsSendUntilSnapshotRepair() {
        Fixture fixture = Fixture.create();
        fixture.refreshAlliance(900L, 1, Set.of(10001L));
        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L), ignored -> {
        });
        fixture.executor.runAll();
        fixture.allianceAwareness.onAllianceChanged(new AllianceMemberChangedEvent(
                900L,
                10001L,
                AllianceMemberAction.JOIN,
                3
        ));
        fixture.executor.runAll();
        AtomicReference<ChatSendResult> stale = new AtomicReference<>();
        AtomicReference<ChatSendResult> repaired = new AtomicReference<>();

        fixture.channels.send(new ChatSendRequest(ChatChannelIds.alliance(900L), 10001L, "stale", 0), stale::set);
        fixture.executor.runAll();
        fixture.allianceAwareness.refresh(new AllianceSnapshot(900L, 3, Set.of(10001L)));
        fixture.executor.runAll();
        fixture.channels.send(new ChatSendRequest(ChatChannelIds.alliance(900L), 10001L, "repaired", 0), repaired::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.STALE_ALLIANCE, stale.get().status());
        assertEquals(ChatSendStatus.SENT, repaired.get().status());
        assertEquals(List.of("repaired"),
                fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().history().stream()
                        .map(ChatDelivery::text)
                        .toList());
    }

    @Test
    void leavingAllianceSnapshotRemovesExistingChannelMemberBeforeSend() {
        Fixture fixture = Fixture.create();
        fixture.refreshAlliance(900L, 1, Set.of(10001L));
        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L), ignored -> {
        });
        fixture.executor.runAll();
        fixture.allianceAwareness.refresh(new AllianceSnapshot(900L, 2, Set.of()));
        fixture.executor.runAll();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.channels.send(new ChatSendRequest(ChatChannelIds.alliance(900L), 10001L, "after leave", 0), sent::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.NOT_IN_CHANNEL, sent.get().status());
        assertEquals(List.of(), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().history());
        assertEquals(Set.of(), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members());
    }

    @Test
    void allianceLeaveEventRemovesExistingChannelMember() {
        Fixture fixture = Fixture.create();
        fixture.refreshAlliance(900L, 1, Set.of(10001L));
        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L), ignored -> {
        });
        fixture.executor.runAll();

        fixture.allianceAwareness.onAllianceChanged(new AllianceMemberChangedEvent(
                900L,
                10001L,
                AllianceMemberAction.LEAVE,
                2
        ));
        fixture.executor.runAll();

        assertEquals(Set.of(), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members());
        assertEquals(0, fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().history().size());
        assertEquals(1, fixture.channels.stats().allianceRemovedMembers());
        assertEquals(1, fixture.channels.stats().allianceEventRemovedMembers());
        assertEquals(0, fixture.channels.stats().allianceSnapshotRemovedMembers());
    }

    @Test
    void allianceSnapshotRetainsOnlyCurrentMembersInExistingChannel() {
        Fixture fixture = Fixture.create();
        fixture.refreshAlliance(900L, 1, Set.of(10001L, 10002L));
        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L), ignored -> {
        });
        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10002L, 900L), ignored -> {
        });
        fixture.executor.runAll();

        fixture.allianceAwareness.refresh(new AllianceSnapshot(900L, 2, Set.of(10002L)));
        fixture.executor.runAll();

        assertEquals(Set.of(10002L), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members());
        assertEquals(1, fixture.channels.stats().allianceRemovedMembers());
        assertEquals(0, fixture.channels.stats().allianceEventRemovedMembers());
        assertEquals(1, fixture.channels.stats().allianceSnapshotRemovedMembers());
    }

    @Test
    void allianceEventDoesNotAffectChannelAccessBeforeAwarenessMailboxRuns() {
        Fixture fixture = Fixture.create();
        fixture.allianceAwareness.enter(10001L);
        fixture.allianceAwareness.watchAlliance(900L);
        fixture.executor.runAll();
        AllianceAwareChatChannelAccessPolicy policy = new AllianceAwareChatChannelAccessPolicy(fixture.allianceAwareness);
        ChatJoinRequest request = new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L);

        fixture.allianceAwareness.onAllianceChanged(new AllianceMemberChangedEvent(
                900L,
                10001L,
                AllianceMemberAction.JOIN,
                1
        ));

        assertTrue(fixture.allianceAwareness.allianceOf(10001L).isEmpty());
        assertEquals(ChatJoinStatus.STALE_ALLIANCE, policy.inspectJoin(request));

        fixture.executor.runAll();

        assertEquals(ChatJoinStatus.JOINED, policy.inspectJoin(request));
    }

    @Test
    void allianceEventStrictJoinPolicyRefreshesSnapshotBeforeReturningDecision() {
        Fixture fixture = Fixture.create(allianceId -> java.util.Optional.of(
                new AllianceSnapshot(allianceId, 3, Set.of(10001L, 20002L))
        ));
        fixture.allianceAwareness.enter(10001L);
        fixture.allianceAwareness.watchAlliance(900L);
        fixture.executor.runAll();
        AllianceAwareChatChannelAccessPolicy policy = new AllianceAwareChatChannelAccessPolicy(fixture.allianceAwareness);
        AtomicReference<ChatJoinStatus> status = new AtomicReference<>();

        policy.inspectJoinAfterAllianceChanged(
                new AllianceMemberChangedEvent(900L, 10001L, AllianceMemberAction.JOIN, 3),
                new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L),
                status::set
        );

        assertEquals(null, status.get());

        fixture.executor.runAll();

        assertEquals(ChatJoinStatus.JOINED, status.get());
        assertEquals(new com.commonbattle.game.scene.SceneProjectionStats(1, 1, 0, 1, 1, 1, 0, 0,
                1, 0, 1, 0, 0), fixture.allianceAwareness.projectionStats());
    }

    @Test
    void allianceEventStrictSendPolicyRejectsAfterFreshLeaveSnapshot() {
        Fixture fixture = Fixture.create(allianceId -> java.util.Optional.of(
                new AllianceSnapshot(allianceId, 3, Set.of())
        ));
        fixture.allianceAwareness.enter(10001L);
        fixture.allianceAwareness.watchAlliance(900L);
        fixture.executor.runAll();
        AllianceAwareChatChannelAccessPolicy policy = new AllianceAwareChatChannelAccessPolicy(fixture.allianceAwareness);
        AtomicReference<ChatSendStatus> status = new AtomicReference<>();

        policy.inspectSendAfterAllianceChanged(
                new AllianceMemberChangedEvent(900L, 10001L, AllianceMemberAction.LEAVE, 3),
                new ChatSendRequest(ChatChannelIds.alliance(900L), 10001L, "alliance", 0),
                status::set
        );
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.NOT_ALLIANCE_MEMBER, status.get());
        assertEquals(1, fixture.allianceAwareness.projectionStats().freshReadRefreshes());
    }

    @Test
    void allianceEventStrictPolicyReportsStaleWhenRemoteSnapshotIsNotFreshEnough() {
        Fixture fixture = Fixture.create(allianceId -> java.util.Optional.of(
                new AllianceSnapshot(allianceId, 2, Set.of(10001L))
        ));
        fixture.allianceAwareness.enter(10001L);
        fixture.allianceAwareness.watchAlliance(900L);
        fixture.executor.runAll();
        AllianceAwareChatChannelAccessPolicy policy = new AllianceAwareChatChannelAccessPolicy(fixture.allianceAwareness);
        AtomicReference<ChatSendStatus> status = new AtomicReference<>();

        policy.inspectSendAfterAllianceChanged(
                new AllianceMemberChangedEvent(900L, 10001L, AllianceMemberAction.JOIN, 3),
                new ChatSendRequest(ChatChannelIds.alliance(900L), 10001L, "alliance", 0),
                status::set
        );
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.STALE_ALLIANCE, status.get());
        assertEquals(1, fixture.allianceAwareness.projectionStats().freshReadRemoteStale());
    }

    @Test
    void channelManagerJoinsAfterAllianceEventFreshReadInsideChannelActor() {
        Fixture fixture = Fixture.create(allianceId -> java.util.Optional.of(
                new AllianceSnapshot(allianceId, 3, Set.of(10001L, 20002L))
        ));
        fixture.allianceAwareness.enter(10001L);
        fixture.allianceAwareness.watchAlliance(900L);
        fixture.executor.runAll();
        AtomicReference<ChatJoinResult> joined = new AtomicReference<>();

        fixture.channels.joinAfterAllianceChanged(
                new AllianceMemberChangedEvent(900L, 10001L, AllianceMemberAction.JOIN, 3),
                new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L),
                joined::set
        );

        assertEquals(null, joined.get());

        fixture.executor.runAll();

        assertEquals(ChatJoinStatus.JOINED, joined.get().status());
        assertEquals(Set.of(10001L), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members());
        assertEquals(1, fixture.channels.stats().asyncAccessChecks());
        assertEquals(1, fixture.channels.stats().asyncAccessAllowedDecisions());
        assertEquals(0, fixture.channels.stats().asyncAccessRejectedDecisions());
    }

    @Test
    void asyncAllianceAccessReadsOutsideChannelMailboxAndReentersForJoin() {
        Fixture fixture = Fixture.createAsyncAccess();
        fixture.refreshAlliance(900L, 1, Set.of(10001L));
        AtomicReference<ChatJoinResult> joined = new AtomicReference<>();

        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L), joined::set);

        assertEquals(null, joined.get());
        assertEquals(1, fixture.executor.queued());

        fixture.executor.runNext();

        assertEquals(null, joined.get());
        assertEquals(1, fixture.executor.queued());

        fixture.executor.runNext();

        assertEquals(ChatJoinStatus.JOINED, joined.get().status());
        assertEquals(Set.of(10001L), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members());
    }

    @Test
    void asyncAllianceAccessReportsBackpressureWhenChannelMailboxCannotAcceptCallback() {
        Fixture fixture = Fixture.createAsyncAccessWithMailboxCapacity(1);
        fixture.refreshAlliance(900L, 1, Set.of(10001L));
        AtomicReference<ChatJoinResult> joined = new AtomicReference<>();

        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L), joined::set);
        fixture.actors.send(fixture.actors.actor(ChatActorIds.channelActorId(ChatChannelIds.alliance(900L))), ignored -> {
        });

        assertEquals(2, fixture.executor.queued());

        fixture.executor.runNext();

        assertEquals(ChatJoinStatus.BACKPRESSURED, joined.get().status());
        assertEquals(Set.of(), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members());
        assertEquals(1, fixture.channels.stats().asyncAccessChecks());
        assertEquals(1, fixture.channels.stats().asyncAccessAllowedDecisions());
        assertEquals(1, fixture.channels.stats().asyncAccessCallbackDeliveryFailures());

        fixture.executor.runAll();
    }

    @Test
    void channelManagerSeesFreshAllianceLeaveBeforeFinalSend() {
        Fixture fixture = Fixture.create(allianceId -> java.util.Optional.of(
                new AllianceSnapshot(allianceId, 3, Set.of())
        ));
        fixture.refreshAlliance(900L, 1, Set.of(10001L));
        fixture.channels.join(new ChatJoinRequest(ChatChannelIds.alliance(900L), 10001L, 900L), ignored -> {
        });
        fixture.executor.runAll();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.channels.sendAfterAllianceChanged(
                new AllianceMemberChangedEvent(900L, 10001L, AllianceMemberAction.LEAVE, 3),
                new ChatSendRequest(ChatChannelIds.alliance(900L), 10001L, "after leave", 0),
                sent::set
        );
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.NOT_IN_CHANNEL, sent.get().status());
        assertEquals(Set.of(), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members());
        assertEquals(List.of(), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().history());
    }

    private record Fixture(
            RecordingExecutor executor,
            ActorSystem actors,
            SceneAllianceAwarenessAgent allianceAwareness,
            ChatChannelManager channels
    ) {
        private static Fixture create() {
            return create(allianceId -> java.util.Optional.empty());
        }

        private static Fixture createAsyncAccess() {
            return create(allianceId -> java.util.Optional.empty(), true);
        }

        private static Fixture createAsyncAccessWithMailboxCapacity(int mailboxCapacity) {
            return create(allianceId -> java.util.Optional.empty(), true, mailboxCapacity);
        }

        private static Fixture create(com.commonbattle.game.social.AllianceSnapshotReader snapshotReader) {
            return create(snapshotReader, false);
        }

        private static Fixture create(
                com.commonbattle.game.social.AllianceSnapshotReader snapshotReader,
                boolean asyncAccess
        ) {
            return create(snapshotReader, asyncAccess, com.commonbattle.actor.ActorSystemConfig.DEFAULT_MAILBOX_CAPACITY);
        }

        private static Fixture create(
                com.commonbattle.game.social.AllianceSnapshotReader snapshotReader,
                boolean asyncAccess,
                int mailboxCapacity
        ) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64, mailboxCapacity);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            SceneAllianceAwarenessAgent allianceAwareness = new SceneAllianceAwarenessAgent(
                    messages,
                    actors.actor("chat-alliance-awareness"),
                    com.commonbattle.game.event.OwnerEventInterestControl.noop(),
                    snapshotReader
            );
            ScenePlayerInterestCoordinator interests = new ScenePlayerInterestCoordinator(
                    new SceneProfileAwarenessAgent(messages, actors.actor("chat-profile-awareness"), ProfileInterestControl.noop()),
                    new SceneFriendAwarenessAgent(messages, actors.actor("chat-friend-awareness")),
                    new ScenePlayerDomainEventAgent(
                            messages,
                            actors.actor("chat-domain-awareness"),
                            new PlayerDomainEventProcessor()
                    ),
                    allianceAwareness
            );
            ChatMessagePolicy messagePolicy = request ->
                    ChatMessageDecision.sent("player-" + request.senderId(), request.text().trim());
            ChatChannelManager channels = new ChatChannelManager(
                    actors,
                    messages,
                    interests,
                    new AllianceAwareChatChannelAccessPolicy(allianceAwareness),
                    messagePolicy,
                    ChatDeliverySink.noop(),
                    CLOCK,
                    ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES,
                    (target, operation) -> com.commonbattle.actor.backpressure.AdmissionDecision.accept()
            );
            if (asyncAccess) {
                AllianceAwareChatChannelAccessPolicy accessPolicy =
                        new AllianceAwareChatChannelAccessPolicy(allianceAwareness);
                channels = new ChatChannelManager(
                        actors,
                        messages,
                        interests,
                        accessPolicy,
                        accessPolicy,
                        messagePolicy,
                        null,
                        ChatDeliverySink.noop(),
                        CLOCK,
                        ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES,
                        (target, operation) -> com.commonbattle.actor.backpressure.AdmissionDecision.accept()
                );
            }
            allianceAwareness.attachMembershipChanges(new ChatAllianceMembershipProjector(channels));
            return new Fixture(executor, actors, allianceAwareness, channels);
        }

        private void refreshAlliance(long allianceId, long revision, Set<Long> members) {
            allianceAwareness.watchAlliance(allianceId);
            executor.runAll();
            members.forEach(allianceAwareness::enter);
            executor.runAll();
            allianceAwareness.refresh(new AllianceSnapshot(allianceId, revision, members));
            executor.runAll();
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

        private void runNext() {
            commands.removeFirst().run();
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
