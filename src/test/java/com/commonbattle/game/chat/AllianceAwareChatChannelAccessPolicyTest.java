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
        fixture.allianceAwareness.handleAllianceChanged(new AllianceMemberChangedEvent(
                900L,
                10001L,
                AllianceMemberAction.JOIN,
                3
        ));
        AtomicReference<ChatSendResult> stale = new AtomicReference<>();
        AtomicReference<ChatSendResult> repaired = new AtomicReference<>();

        fixture.channels.send(new ChatSendRequest(ChatChannelIds.alliance(900L), 10001L, "stale", 0), stale::set);
        fixture.executor.runAll();
        fixture.allianceAwareness.handleSnapshot(new AllianceSnapshot(900L, 3, Set.of(10001L)));
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
        fixture.allianceAwareness.handleSnapshot(new AllianceSnapshot(900L, 2, Set.of()));
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

        fixture.allianceAwareness.handleAllianceChanged(new AllianceMemberChangedEvent(
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

        fixture.allianceAwareness.handleSnapshot(new AllianceSnapshot(900L, 2, Set.of(10002L)));
        fixture.executor.runAll();

        assertEquals(Set.of(10002L), fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members());
        assertEquals(1, fixture.channels.stats().allianceRemovedMembers());
        assertEquals(0, fixture.channels.stats().allianceEventRemovedMembers());
        assertEquals(1, fixture.channels.stats().allianceSnapshotRemovedMembers());
    }

    private record Fixture(
            RecordingExecutor executor,
            SceneAllianceAwarenessAgent allianceAwareness,
            ChatChannelManager channels
    ) {
        private static Fixture create() {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            SceneAllianceAwarenessAgent allianceAwareness = new SceneAllianceAwarenessAgent(
                    messages,
                    actors.actor("chat-alliance-awareness")
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
            allianceAwareness.attachMembershipChanges(new ChatAllianceMembershipProjector(channels));
            return new Fixture(executor, allianceAwareness, channels);
        }

        private void refreshAlliance(long allianceId, long revision, Set<Long> members) {
            allianceAwareness.watchAlliance(allianceId);
            executor.runAll();
            members.forEach(allianceAwareness::enter);
            executor.runAll();
            allianceAwareness.handleSnapshot(new AllianceSnapshot(allianceId, revision, members));
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
