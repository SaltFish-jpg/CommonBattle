package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.event.OwnerEventInterestControl;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.social.AllianceOwnerKeyParser;
import com.commonbattle.game.social.FriendOwnerKeyParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScenePlayerInterestCoordinatorTest {
    @Test
    void enterAndLeaveDriveAllVisibleDataInterests() {
        Fixture fixture = Fixture.create();
        ScenePlayerInterest interest = ScenePlayerInterest.withAlliance(10001L, "scene-1:chunk-1", 100L);

        fixture.coordinator.enter(interest);
        fixture.executor.runAll();

        assertEquals(List.of(10001L), fixture.profileInterests.watched);
        assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), fixture.friendInterests.watched);
        assertEquals(List.of(PlayerDomainVersionedEvent.ownerKey(10001L)), fixture.domainInterests.watched);
        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100L)), fixture.allianceInterests.watched);
        assertEquals(new ScenePlayerInterestStats(1, 1, 1, 1, 0, 0, 0, 0), fixture.coordinator.interestStats());

        fixture.coordinator.leave(interest);
        fixture.executor.runAll();

        assertEquals(List.of(10001L), fixture.profileInterests.unwatched);
        assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), fixture.friendInterests.unwatched);
        assertEquals(List.of(PlayerDomainVersionedEvent.ownerKey(10001L)), fixture.domainInterests.unwatched);
        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100L)), fixture.allianceInterests.unwatched);
        assertEquals(new ScenePlayerInterestStats(0, 0, 0, 1, 0, 1, 0, 0), fixture.coordinator.interestStats());
    }

    @Test
    void duplicateEnterIsIdempotentForSameInterestKey() {
        Fixture fixture = Fixture.create();
        ScenePlayerInterest interest = ScenePlayerInterest.withAlliance(10001L, "scene-1:chunk-1", 100L);

        fixture.coordinator.enter(interest);
        fixture.coordinator.enter(interest);
        fixture.executor.runAll();

        assertEquals(List.of(10001L), fixture.profileInterests.watched);
        assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), fixture.friendInterests.watched);
        assertEquals(List.of(PlayerDomainVersionedEvent.ownerKey(10001L)), fixture.domainInterests.watched);
        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100L)), fixture.allianceInterests.watched);
        assertEquals(new ScenePlayerInterestStats(1, 1, 1, 1, 1, 0, 0, 0), fixture.coordinator.interestStats());
    }

    @Test
    void multipleInterestKeysKeepSharedPlayerSubscriptionsUntilLastLeave() {
        Fixture fixture = Fixture.create();
        ScenePlayerInterest first = ScenePlayerInterest.withAlliance(10001L, "scene-1:chunk-1", 100L);
        ScenePlayerInterest second = ScenePlayerInterest.withAlliance(10001L, "scene-1:chunk-2", 100L);

        fixture.coordinator.enter(first);
        fixture.coordinator.enter(second);
        fixture.executor.runAll();
        fixture.coordinator.leave(first);
        fixture.executor.runAll();

        assertEquals(List.of(10001L, 10001L), fixture.profileInterests.watched);
        assertEquals(List.of(10001L), fixture.profileInterests.unwatched);
        assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), fixture.friendInterests.watched);
        assertEquals(List.of(), fixture.friendInterests.unwatched);
        assertEquals(List.of(), fixture.domainInterests.unwatched);
        assertEquals(List.of(), fixture.allianceInterests.unwatched);
        assertEquals(new ScenePlayerInterestStats(1, 1, 1, 2, 0, 1, 0, 0), fixture.coordinator.interestStats());

        fixture.coordinator.leave(second);
        fixture.executor.runAll();

        assertEquals(List.of(FriendOwnerKeyParser.ownerKey(10001L)), fixture.friendInterests.unwatched);
        assertEquals(List.of(PlayerDomainVersionedEvent.ownerKey(10001L)), fixture.domainInterests.unwatched);
        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100L)), fixture.allianceInterests.unwatched);
        assertEquals(new ScenePlayerInterestStats(0, 0, 0, 2, 0, 2, 0, 0), fixture.coordinator.interestStats());
    }

    @Test
    void repeatedEnterWithDifferentAllianceSwitchesAllianceWatch() {
        Fixture fixture = Fixture.create();

        fixture.coordinator.enter(ScenePlayerInterest.withAlliance(10001L, "scene-1:chunk-1", 100L));
        fixture.executor.runAll();
        fixture.coordinator.enter(ScenePlayerInterest.withAlliance(10001L, "scene-1:chunk-1", 200L));
        fixture.executor.runAll();

        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100L), AllianceOwnerKeyParser.ownerKey(200L)),
                fixture.allianceInterests.watched);
        assertEquals(List.of(AllianceOwnerKeyParser.ownerKey(100L)), fixture.allianceInterests.unwatched);
        assertEquals(new ScenePlayerInterestStats(1, 1, 1, 1, 1, 0, 0, 1), fixture.coordinator.interestStats());
    }

    @Test
    void missingLeaveIsCountedWithoutTouchingSubscriptions() {
        Fixture fixture = Fixture.create();

        fixture.coordinator.leave(10001L, "missing");
        fixture.executor.runAll();

        assertEquals(List.of(), fixture.profileInterests.unwatched);
        assertEquals(List.of(), fixture.friendInterests.unwatched);
        assertEquals(new ScenePlayerInterestStats(0, 0, 0, 0, 0, 0, 1, 0), fixture.coordinator.interestStats());
    }

    private record Fixture(
            RecordingExecutor executor,
            RecordingProfileInterests profileInterests,
            RecordingOwnerInterests friendInterests,
            RecordingOwnerInterests domainInterests,
            RecordingOwnerInterests allianceInterests,
            ScenePlayerInterestCoordinator coordinator
    ) {
        private static Fixture create() {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            RecordingProfileInterests profileInterests = new RecordingProfileInterests();
            RecordingOwnerInterests friendInterests = new RecordingOwnerInterests();
            RecordingOwnerInterests domainInterests = new RecordingOwnerInterests();
            RecordingOwnerInterests allianceInterests = new RecordingOwnerInterests();
            SceneProfileAwarenessAgent profiles = new SceneProfileAwarenessAgent(
                    messages,
                    actors.actor("scene-profile"),
                    profileInterests
            );
            SceneFriendAwarenessAgent friends = new SceneFriendAwarenessAgent(
                    messages,
                    actors.actor("scene-friends"),
                    friendInterests
            );
            ScenePlayerDomainEventAgent domainEvents = new ScenePlayerDomainEventAgent(
                    messages,
                    actors.actor("scene-domain"),
                    new com.commonbattle.game.player.event.PlayerDomainEventProcessor(),
                    domainInterests
            );
            SceneAllianceAwarenessAgent alliances = new SceneAllianceAwarenessAgent(
                    messages,
                    actors.actor("scene-alliances"),
                    allianceInterests
            );
            return new Fixture(
                    executor,
                    profileInterests,
                    friendInterests,
                    domainInterests,
                    allianceInterests,
                    new ScenePlayerInterestCoordinator(profiles, friends, domainEvents, alliances)
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
        private final List<String> watched = new ArrayList<>();
        private final List<String> unwatched = new ArrayList<>();

        @Override
        public void watchOwner(String ownerKey) {
            watched.add(ownerKey);
        }

        @Override
        public void unwatchOwner(String ownerKey) {
            unwatched.add(ownerKey);
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
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
