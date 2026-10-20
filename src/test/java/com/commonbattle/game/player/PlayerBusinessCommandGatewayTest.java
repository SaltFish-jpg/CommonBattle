package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionControlledAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.rpc.RpcNoRoutableServiceException;
import com.commonbattle.cluster.rpc.RpcStructuredException;
import com.commonbattle.cluster.rpc.RpcTimeoutException;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.boot.BootPayloadCodecs;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.network.ClusterMessageHandler;
import com.commonbattle.cluster.network.ClusterTransport;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.ProtoClusterCodec;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.game.activity.ActivityCatalog;
import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.battle.BattleService;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.battle.BattleSettlementStatus;
import com.commonbattle.game.battle.BattleStageCatalog;
import com.commonbattle.game.battle.BattleStageDefinition;
import com.commonbattle.game.growth.GrowthService;
import com.commonbattle.game.shop.ShopCatalog;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopPurchaseStatus;
import com.commonbattle.game.shop.ShopService;
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerCommandSequencerSnapshot;
import com.commonbattle.game.session.PlayerCommandStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerBusinessCommandGatewayTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void localSubmitWaitsForMailboxBusinessResponse() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback callback = new RecordingCallback();
        PlayerCommand command = command(1);

        fixture.commands.submit(command, callback);

        assertNull(callback.response.get());
        assertEquals(1, fixture.responses.pendingResponses());
        fixture.executor.runAll();

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, callback.response.get().status());
        assertEquals(PlayerBusinessAck.OK, callback.response.get().payload());
        assertEquals(0, fixture.responses.pendingResponses());
    }

    @Test
    void duplicateSubmitWhilePendingSharesMailboxResponse() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback first = new RecordingCallback();
        RecordingCallback retry = new RecordingCallback();
        PlayerCommand command = command(1);

        fixture.commands.submit(command, first);
        fixture.commands.submit(command, retry);

        assertNull(first.response.get());
        assertNull(retry.response.get());
        assertEquals(1, fixture.responses.pendingResponses());
        assertEquals(1, fixture.responses.stats().sharedWaiters());
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.DUPLICATE));

        fixture.executor.runAll();

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.response.get().status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, retry.response.get().status());
        assertEquals(PlayerBusinessAck.OK, first.response.get().payload());
        assertEquals(PlayerBusinessAck.OK, retry.response.get().payload());
        assertEquals(0, fixture.responses.pendingResponses());
        assertEquals(1, fixture.responses.stats().completedResponses());
    }

    @Test
    void duplicateSubmitAfterCompletionReplaysCachedResponse() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback first = new RecordingCallback();
        RecordingCallback retry = new RecordingCallback();
        PlayerCommand command = command(1);

        fixture.commands.submit(command, first);
        fixture.executor.runAll();
        fixture.commands.submit(command, retry);

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.response.get().status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, retry.response.get().status());
        assertEquals(PlayerBusinessAck.OK, retry.response.get().payload());
        assertEquals(0, fixture.responses.pendingResponses());
        assertEquals(0, fixture.executor.queued());
        assertEquals(1, fixture.responses.stats().completedResponses());
        assertEquals(1, fixture.responses.stats().replayedResponses());
        assertEquals(1, fixture.responses.stats().cachedResponses());
    }

    @Test
    void migratedCompletedResponseReplaysBeforeTargetDispatcherRuns() {
        Fixture source = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        Fixture target = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-2"), new NoopRpcGateway());
        PlayerCommand command = command(1);
        RecordingCallback first = new RecordingCallback();
        source.commands.submit(command, first);
        source.executor.runAll();
        target.responses.restore(source.responses.snapshotForPlayer(command.playerId()));
        target.sequencer.restore(source.sequencer.snapshotForPlayer(command.playerId()));
        RecordingCallback retry = new RecordingCallback();

        target.commands.submit(command, retry);

        assertEquals(PlayerBusinessAck.OK, retry.response.get().payload());
        assertEquals(0, target.executor.queued());
        assertEquals(0, target.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, target.responses.stats().replayedResponses());
    }

    @Test
    void duplicateKnownOnlyFromRestoredWatermarkFailsFastWithoutWaitingForCachedResponse() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback callback = new RecordingCallback();
        fixture.sequencer.restore(new PlayerCommandSequencerSnapshot(10001L, List.of(
                new PlayerCommandSequencerSnapshot.Watermark(10001L, "session-1", 1, 1)
        )));

        fixture.commands.submit(command(1), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals("DUPLICATE", callback.response.get().code());
        assertEquals("duplicate:WATERMARK_ONLY", callback.response.get().message());
        assertEquals(0, fixture.responses.pendingResponses());
        assertEquals(0, fixture.executor.queued());
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.DUPLICATE));
    }

    @Test
    void queuedPlayerCommandsExposeHotPlayerMailboxBeforeExecution() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();
        RecordingCallback third = new RecordingCallback();

        fixture.commands.submit(command(1), first);
        fixture.commands.submit(command(2), second);
        fixture.commands.submit(command(3), third);

        assertNull(first.response.get());
        assertNull(second.response.get());
        assertNull(third.response.get());
        assertEquals(1, fixture.executor.queued());
        assertEquals(3, fixture.responses.pendingResponses());
        assertEquals(3, fixture.actors.stats().queuedTasks());
        assertEquals("player-10001", fixture.actors.stats().largestMailboxActorId());
        var hotMailbox = fixture.actors.queuedMailboxStats().getFirst();
        assertEquals("player-10001", hotMailbox.actor().id());
        assertEquals(3, hotMailbox.queuedTasks());
        assertEquals(3, hotMailbox.queuedTasksByCategory().get(ActorTaskCategory.PLAYER_COMMAND));

        fixture.executor.runAll();

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.response.get().status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, second.response.get().status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, third.response.get().status());
        assertEquals(0, fixture.responses.pendingResponses());
        assertEquals(0, fixture.actors.stats().queuedTasks());
    }

    @Test
    void remoteEndpointRespondsAfterOwnerMailboxExecutesCommand() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        RecordingCallback callback = new RecordingCallback();

        gameOneGateway.call(
                RpcRequest.toService(
                        gameTwo.id(),
                        PlayerBusinessRpcOperations.DISPATCH,
                        command(1),
                        PlayerBusinessResponse.class
                ),
                callback
        );

        assertNull(callback.response.get());
        assertEquals(1, owner.responses.pendingResponses());
        owner.executor.runAll();

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, callback.response.get().status());
        assertEquals(PlayerBusinessAck.OK, callback.response.get().payload());
        assertEquals(1, callback.response.get().sequence());
        assertEquals(0, owner.responses.pendingResponses());
    }

    @Test
    void remoteEndpointReturnsCachedCommandResultWithoutReenteringMailbox() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        PlayerCommand command = command(1);
        RecordingCallback dispatch = new RecordingCallback();
        RecordingCallback query = new RecordingCallback();

        gameOneGateway.call(
                RpcRequest.toService(
                        gameTwo.id(),
                        PlayerBusinessRpcOperations.DISPATCH,
                        command,
                        PlayerBusinessResponse.class
                ),
                dispatch
        );
        owner.executor.runAll();
        gameOneGateway.call(
                RpcRequest.toService(
                        gameTwo.id(),
                        PlayerBusinessRpcOperations.GET_RESULT,
                        PlayerBusinessResponseQuery.from(command),
                        PlayerBusinessResponse.class
                ),
                query
        );

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, dispatch.response.get().status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, query.response.get().status());
        assertEquals(PlayerBusinessAck.OK, query.response.get().payload());
        assertEquals(1, owner.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(0, owner.executor.queued());
    }

    @Test
    void remoteEndpointResultQueryMissReturnsExplicitFailureWithoutMailboxWork() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        RecordingCallback query = new RecordingCallback();

        gameOneGateway.call(
                RpcRequest.toService(
                        gameTwo.id(),
                        PlayerBusinessRpcOperations.GET_RESULT,
                        PlayerBusinessResponseQuery.from(command(1)),
                        PlayerBusinessResponse.class
                ),
                query
        );

        assertEquals(PlayerBusinessResponseStatus.FAILED, query.response.get().status());
        assertEquals(PlayerBusinessResponse.RESULT_NOT_FOUND, query.response.get().code());
        assertEquals(0, owner.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(0, owner.executor.queued());
    }

    @Test
    void remoteForwardReturnsSerializedShopResultFromOwnerMailbox() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        SerializingClusterTransport transport = new SerializingClusterTransport(BootPayloadCodecs.gameServer());
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture caller = Fixture.createRemoteOwner(gameOne.id(), gameTwo.id(), gameOneGateway);
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        RecordingCallback callback = new RecordingCallback();

        caller.commands.submit(new PlayerCommand(
                10001L,
                "session-1",
                1,
                1,
                PlayerBusinessOperations.SHOP_BUY,
                new BuyShopItemCommand("order-rpc-1", "growth_pack", 1)
        ), callback);

        assertNull(callback.response.get());
        assertEquals(1, owner.responses.pendingResponses());
        owner.executor.runAll();

        PlayerBusinessResponse response = callback.response.get();
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, response.status(), response.code() + ":" + response.message());
        ShopPurchaseResult payload = assertInstanceOf(ShopPurchaseResult.class, response.payload());
        assertEquals(ShopPurchaseStatus.SUCCESS, payload.status());
        assertEquals("growth_pack", payload.sku());
        assertEquals(70, owner.agent.profile().bag().count("gold"));
        assertEquals(2, owner.agent.profile().bag().count("exp_potion"));
        assertTrue(transport.encodedMessages() >= 2);
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwards());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwardSuccessResponses());
    }

    @Test
    void remoteForwardReturnsSerializedBattleResultFromOwnerMailbox() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        SerializingClusterTransport transport = new SerializingClusterTransport(BootPayloadCodecs.gameServer());
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture caller = Fixture.createRemoteOwner(gameOne.id(), gameTwo.id(), gameOneGateway);
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        RecordingCallback callback = new RecordingCallback();

        caller.commands.submit(new PlayerCommand(
                10001L,
                "session-1",
                1,
                1,
                PlayerBusinessOperations.BATTLE_CLEAR_STAGE,
                new BattleStageClearCommand("settle-rpc-1", "forest-1")
        ), callback);

        assertNull(callback.response.get());
        assertEquals(1, owner.responses.pendingResponses());
        owner.executor.runAll();

        PlayerBusinessResponse response = callback.response.get();
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, response.status(), response.code() + ":" + response.message());
        BattleSettlementResult payload = assertInstanceOf(BattleSettlementResult.class, response.payload());
        assertEquals(BattleSettlementStatus.VICTORY, payload.status());
        assertEquals("forest-1", payload.stageId());
        assertEquals("settle-rpc-1", payload.settlementId());
        assertEquals("battle-win-1", payload.progressActivityId());
        assertEquals(1, payload.progressDelta());
        assertEquals(3, payload.stars());
        assertTrue(payload.firstClear());
        assertTrue(payload.log().size() > 0);
        assertEquals(130, owner.agent.profile().bag().count("gold"));
        assertEquals(1, owner.agent.profile().bag().count("exp_potion"));
        assertEquals(5, owner.agent.profile().bag().count("gem"));
        assertTrue(transport.encodedMessages() >= 2);
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwards());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwardSuccessResponses());
    }

    @Test
    void remoteForwardCommitsCallerSequenceAfterOwnerAcceptedCommand() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture caller = Fixture.createRemoteOwner(gameOne.id(), gameTwo.id(), gameOneGateway);
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();

        caller.commands.submit(command(1), first);
        owner.executor.runAll();
        caller.commands.submit(command(2), second);

        assertNull(second.response.get());
        assertEquals(1, owner.responses.pendingResponses());
        owner.executor.runAll();

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.response.get().status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, second.response.get().status());
        assertEquals(2, owner.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(2, caller.dispatcher.stats().count(PlayerCommandStatus.ROUTED_REMOTE));
        assertEquals(0, caller.dispatcher.stats().count(PlayerCommandStatus.GAP));
        assertEquals(PlayerCommandStatus.ACCEPTED, caller.sequencer.inspect(command(3)));
        assertEquals(2, caller.commands.commandGatewayStats().remoteForwards());
        assertEquals(2, caller.commands.commandGatewayStats().remoteForwardSuccessResponses());
    }

    @Test
    void duplicateRemoteForwardWhilePendingSharesOriginalRpcAndMailboxResponse() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture caller = Fixture.createRemoteOwner(gameOne.id(), gameTwo.id(), gameOneGateway);
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        RecordingCallback first = new RecordingCallback();
        RecordingCallback retry = new RecordingCallback();

        caller.commands.submit(command(1), first);
        caller.commands.submit(command(1), retry);

        assertNull(first.response.get());
        assertNull(retry.response.get());
        assertEquals(1, owner.responses.pendingResponses());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwards());
        assertEquals(1, caller.responses.stats().sharedWaiters());
        owner.executor.runAll();

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.response.get().status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, retry.response.get().status());
        assertEquals(1, owner.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, caller.dispatcher.stats().count(PlayerCommandStatus.DUPLICATE));
    }

    @Test
    void duplicateRemoteForwardAfterSuccessReplaysCachedResponseWithoutRpc() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture caller = Fixture.createRemoteOwner(gameOne.id(), gameTwo.id(), gameOneGateway);
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        RecordingCallback first = new RecordingCallback();
        RecordingCallback retry = new RecordingCallback();

        caller.commands.submit(command(1), first);
        owner.executor.runAll();
        caller.commands.submit(command(1), retry);

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.response.get().status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, retry.response.get().status());
        assertEquals(1, owner.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwards());
        assertEquals(1, caller.responses.stats().replayedResponses());
    }

    @Test
    void remoteForwardTimeoutRecoversLostOwnerResponseFromOwnerCache() throws Exception {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        DroppingFirstRpcSuccessTransport transport = new DroppingFirstRpcSuccessTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture caller = Fixture.createRemoteOwner(gameOne.id(), gameTwo.id(), gameOneGateway, Duration.ofMillis(10));
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        try {
            RecordingCallback first = new RecordingCallback();
            PlayerCommand command = command(1);

            caller.commands.submit(command, first);
            owner.executor.runAll();

            assertTrue(await(() -> first.response.get() != null));
            assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.response.get().status());
            assertEquals(PlayerBusinessAck.OK, first.response.get().payload());
            assertEquals(1, owner.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
            assertEquals(PlayerCommandStatus.ACCEPTED, caller.sequencer.inspect(command(2)));
            assertEquals(1, caller.commands.commandGatewayStats().remoteForwards());
            assertEquals(0, caller.commands.commandGatewayStats().remoteForwardTimedOutResponses());
            assertEquals(1, caller.commands.commandGatewayStats().remoteForwardSuccessResponses());
            assertEquals(1, caller.commands.commandGatewayStats().remoteForwardTimeoutRecoveryQueries());
            assertEquals(1, caller.commands.commandGatewayStats().remoteForwardTimeoutRecoveryHits());
            assertEquals(0, caller.commands.commandGatewayStats().remoteForwardTimeoutRecoveryMisses());
        } finally {
            caller.commands.close();
        }
    }

    @Test
    void remoteForwardTimeoutReturnsTimeoutWhenOwnerResultCacheMisses() throws Exception {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture caller = Fixture.createRemoteOwner(gameOne.id(), gameTwo.id(), gameOneGateway, Duration.ofMillis(10));
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        try {
            RecordingCallback callback = new RecordingCallback();

            caller.commands.submit(command(1), callback);

            assertTrue(await(() -> callback.response.get() != null));
            assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
            assertEquals(PlayerBusinessResponse.TIMEOUT, callback.response.get().code());
            assertEquals(1, owner.responses.pendingResponses());
            assertEquals(1, caller.commands.commandGatewayStats().remoteForwardTimeoutRecoveryQueries());
            assertEquals(0, caller.commands.commandGatewayStats().remoteForwardTimeoutRecoveryHits());
            assertEquals(1, caller.commands.commandGatewayStats().remoteForwardTimeoutRecoveryMisses());
            assertEquals(1, caller.commands.commandGatewayStats().remoteForwardTimedOutResponses());

            owner.executor.runAll();
            assertEquals(PlayerBusinessResponse.TIMEOUT, callback.response.get().code());
        } finally {
            caller.commands.close();
        }
    }

    @Test
    void remoteForwardTimeoutReturnsClassifiedFailureEnvelope() {
        Duration timeout = Duration.ofMillis(25);
        Fixture fixture = Fixture.createRemoteOwner(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                new FailingRpcGateway(request -> new RpcTimeoutException(request, timeout))
        );
        RecordingCallback callback = new RecordingCallback();

        fixture.commands.submit(command(1), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals(PlayerBusinessResponse.REMOTE_TIMEOUT, callback.response.get().code());
        assertEquals(timeout.toMillis(), callback.response.get().retryAfterMillis());
        assertEquals(0, fixture.responses.pendingResponses());
        assertEquals(PlayerCommandStatus.ACCEPTED, fixture.sequencer.inspect(command(1)));
    }

    @Test
    void remoteForwardUnavailableReturnsClassifiedFailureEnvelope() {
        Fixture fixture = Fixture.createRemoteOwner(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                new FailingRpcGateway(RpcNoRoutableServiceException::new)
        );
        RecordingCallback callback = new RecordingCallback();

        fixture.commands.submit(command(1), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals(PlayerBusinessResponse.REMOTE_UNAVAILABLE, callback.response.get().code());
        assertEquals(0, fixture.responses.pendingResponses());
        assertEquals(PlayerCommandStatus.ACCEPTED, fixture.sequencer.inspect(command(1)));
    }

    @Test
    void remoteForwardStructuredFailureKeepsCodeAndRetryAfter() {
        Fixture fixture = Fixture.createRemoteOwner(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                new FailingRpcGateway(request -> new RpcStructuredException(
                        "REMOTE_MAINTENANCE",
                        "maintenance",
                        Duration.ofMillis(80)
                ))
        );
        RecordingCallback callback = new RecordingCallback();

        fixture.commands.submit(command(1), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals("REMOTE_MAINTENANCE", callback.response.get().code());
        assertEquals("maintenance", callback.response.get().message());
        assertEquals(80, callback.response.get().retryAfterMillis());
        assertEquals(0, fixture.responses.pendingResponses());
    }

    @Test
    void remoteForwardToMigratingOwnerReturnsRetryableProtectionFailure() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture caller = Fixture.createRemoteOwner(gameOne.id(), gameTwo.id(), gameOneGateway);
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        owner.lifecycles.migrate(
                AgentIdentity.player(10001L),
                new AgentLocation(ServiceId.of(ServiceKind.GAME, "r1", "game-3"), new ActorRef("player-10001")),
                ignored -> {
                    throw new AssertionError("migration snapshot should stay queued during ingress protection");
                }
        );
        RecordingCallback callback = new RecordingCallback();

        caller.commands.submit(command(1), callback);

        PlayerBusinessResponse response = callback.response.get();
        assertEquals(PlayerBusinessResponseStatus.FAILED, response.status());
        assertEquals(PlayerCommandStatus.AGENT_MIGRATING.name(), response.code());
        assertEquals("agent_migrating", response.message());
        assertEquals(100, response.retryAfterMillis());
        assertEquals(0, owner.responses.pendingResponses());
        assertEquals(0, owner.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, owner.dispatcher.stats().count(PlayerCommandStatus.AGENT_MIGRATING));
        assertEquals(1, owner.executor.queued());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwards());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwardSuccessResponses());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwardMigrationProtectedResponses());
    }

    @Test
    void remoteForwardToMigratedOldOwnerRetriesRedirectedOwnerOnce() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        SerializingClusterTransport transport = new SerializingClusterTransport(BootPayloadCodecs.gameServer());
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        ServiceDescriptor gameThree = descriptor("game-3", 9103);
        registry.register(gameOne);
        registry.register(gameTwo);
        registry.register(gameThree);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        gameOneDirectory.seed(gameThree);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterDirectory gameThreeDirectory = new ClusterDirectory(registry);
        gameThreeDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        ClusterRpcGateway gameThreeGateway = new ClusterRpcGateway(gameThree, gameThreeDirectory, topology, transport);
        Fixture caller = Fixture.createRemoteOwner(gameOne.id(), gameTwo.id(), gameOneGateway);
        Fixture oldOwner = Fixture.create(gameTwo.id(), gameTwoGateway);
        Fixture newOwner = Fixture.create(gameThree.id(), gameThreeGateway);
        new PlayerBusinessCommandEndpoint(oldOwner.commands).bind(gameTwoGateway);
        new PlayerBusinessCommandEndpoint(newOwner.commands).bind(gameThreeGateway);
        oldOwner.lifecycles.migrate(
                AgentIdentity.player(10001L),
                new AgentLocation(gameThree.id(), new ActorRef("player-10001")),
                ignored -> {
                }
        );
        oldOwner.executor.runAll();
        RecordingCallback callback = new RecordingCallback();

        caller.commands.submit(command(1), callback);

        assertNull(callback.response.get());
        assertEquals(1, newOwner.responses.pendingResponses());
        newOwner.executor.runAll();

        PlayerBusinessResponse response = callback.response.get();
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, response.status(), response.code() + ":" + response.message());
        assertEquals(PlayerBusinessAck.OK, response.payload());
        assertEquals(0, oldOwner.responses.pendingResponses());
        assertEquals(0, oldOwner.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, newOwner.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwards());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwardSuccessResponses());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwardMigrationProtectedResponses());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwardRedirectResponses());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwardRedirectRetries());
        assertEquals(1, caller.commands.commandGatewayStats().remoteForwardRedirectRouteRefreshes());
        assertEquals(gameThree.id(), caller.directory.locate(AgentIdentity.player(10001L))
                .orElseThrow()
                .serviceId());
        assertTrue(transport.encodedMessages() >= 4);
    }

    @Test
    void remoteForwardWithoutRpcCallbackTimesOutAndIgnoresLateResponse() throws Exception {
        HangingRpcGateway rpc = new HangingRpcGateway();
        Duration timeout = Duration.ofMillis(10);
        Fixture fixture = Fixture.createRemoteOwner(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                rpc,
                timeout
        );
        try {
            RecordingCallback callback = new RecordingCallback();
            PlayerCommand command = command(1);

            fixture.commands.submit(command, callback);

            assertTrue(await(() -> callback.response.get() != null));
            assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
            assertEquals(PlayerBusinessResponse.TIMEOUT, callback.response.get().code());
            assertEquals(0, fixture.responses.pendingResponses());
            assertEquals(1, fixture.commands.commandGatewayStats().remoteForwards());
            assertEquals(1, fixture.commands.commandGatewayStats().remoteForwardTimedOutResponses());
            assertEquals(1, fixture.commands.commandGatewayStats().remoteForwardTimeoutRecoveryQueries());
            assertEquals(1, fixture.commands.commandGatewayStats().remoteForwardTimeoutRecoveryFailures());

            rpc.succeed(PlayerBusinessResponse.success(command, PlayerBusinessAck.OK));

            assertEquals(PlayerBusinessResponse.TIMEOUT, callback.response.get().code());
            assertEquals(1, fixture.commands.commandGatewayStats().lateRemoteForwardResponses());
        } finally {
            fixture.commands.close();
        }
    }

    @Test
    void rejectedLocalCommandReturnsFailureEnvelopeAndClearsWaiter() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback callback = new RecordingCallback();

        fixture.commands.submit(new PlayerCommand(
                10001L,
                "stale-session",
                1,
                3,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 1)
        ), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals("STALE_SESSION", callback.response.get().code());
        assertEquals(0, fixture.responses.pendingResponses());
    }

    @Test
    void commandDuringLocalOwnerMigrationReturnsRetryableFailureWithoutEnteringOldMailbox() {
        ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
        ServiceId remote = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
        Fixture fixture = Fixture.create(local, new NoopRpcGateway());
        RecordingCallback callback = new RecordingCallback();

        fixture.lifecycles.migrate(
                AgentIdentity.player(10001L),
                new AgentLocation(remote, new ActorRef("player-10001")),
                ignored -> {
                }
        );
        fixture.commands.submit(command(1), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals("AGENT_MIGRATING", callback.response.get().code());
        assertEquals(100, callback.response.get().retryAfterMillis());
        assertEquals(0, fixture.responses.pendingResponses());
        assertEquals(1, fixture.executor.queued());
        assertEquals(0, fixture.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.AGENT_MIGRATING));
    }

    @Test
    void commandDuringLocalOwnerPassivationReturnsRetryableFailureWithoutEnteringOldMailbox() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback callback = new RecordingCallback();

        fixture.lifecycles.passivate(AgentIdentity.player(10001L), ignored -> {
        });
        fixture.commands.submit(command(1), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals("AGENT_MIGRATING", callback.response.get().code());
        assertEquals("agent_passivating", callback.response.get().message());
        assertEquals(100, callback.response.get().retryAfterMillis());
        assertEquals(0, fixture.responses.pendingResponses());
        assertEquals(1, fixture.executor.queued());
        assertEquals(0, fixture.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.AGENT_MIGRATING));
    }

    @Test
    void localSubmitTimesOutWhenMailboxDoesNotProduceResponseInTime() throws Exception {
        Fixture fixture = Fixture.create(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                new NoopRpcGateway(),
                Duration.ofMillis(10)
        );
        try {
            RecordingCallback callback = new RecordingCallback();

            fixture.commands.submit(command(1), callback);

            assertTrue(await(() -> callback.response.get() != null));
            assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
            assertEquals(PlayerBusinessResponse.TIMEOUT, callback.response.get().code());
            assertEquals(0, fixture.responses.pendingResponses());
            assertEquals(1, fixture.responses.stats().timedOutResponses());
            fixture.executor.runAll();
            assertEquals(1, fixture.responses.stats().fallbackResponses());
            assertEquals(PlayerBusinessResponse.TIMEOUT, callback.response.get().code());
        } finally {
            fixture.commands.close();
        }
    }

    private static PlayerCommand command(long sequence) {
        return new PlayerCommand(
                10001L,
                "session-1",
                1,
                sequence,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 1)
        );
    }

    private static ServiceDescriptor descriptor(String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(PlayerBusinessRpcOperations.DISPATCH, PlayerBusinessRpcOperations.GET_RESULT),
                Map.of()
        );
    }

    private record Fixture(
            RecordingExecutor executor,
            ActorSystem actors,
            AgentLifecycleManager lifecycles,
            PlayerBusinessResponseHub responses,
            PlayerCommandSequencer sequencer,
            PlayerCommandDispatcher dispatcher,
            PlayerGameAgent agent,
            InMemoryAgentDirectory directory,
            PlayerBusinessCommandGateway commands
    ) {
        private static Fixture create(ServiceId local, RpcGateway rpc) {
            return create(local, rpc, PlayerBusinessCommandGateway.DEFAULT_RESPONSE_TIMEOUT);
        }

        private static Fixture create(ServiceId local, RpcGateway rpc, Duration timeout) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            ActorRef self = actors.actor("player-10001");
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            lifecycles.activate(AgentIdentity.player(10001L), self.id());
            executor.runAll();
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, rpc);
            PlayerProfile profile = new PlayerProfile(10001L, SERVER_OPEN_TIME);
            profile.bag().restore(new BagSnapshot(Map.of("gold", 100)));
            PlayerGameAgent agent = new PlayerGameAgent(
                    messages,
                    self,
                    profile,
                    activityService(),
                    growthService(),
                    shopService(),
                    battleService(),
                    CLOCK,
                    SERVER_OPEN_TIME
            );
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            sessions.bind(10001L, "session-1");
            PlayerCommandSequencer sequencer = new PlayerCommandSequencer();
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    sequencer,
                    new AdmissionControlledAgentRouter(
                            (target, operation) -> AdmissionDecision.accept(),
                            new LifecycleAwareAgentRouter(lifecycles, messages)
                    ),
                    new InMemoryPlayerCommandAuditLog(),
                    ignored -> 1,
                    CLOCK
            );
            PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub();
            PlayerBusinessCommandBinder.registerExamples(
                    dispatcher,
                    new PlayerBusinessCommandHandler(playerId -> agent, responses)
            );
            return new Fixture(executor, actors, lifecycles, responses, sequencer, dispatcher, agent, directory,
                    new PlayerBusinessCommandGateway(dispatcher, rpc, responses, timeout));
        }

        private static Fixture createRemoteOwner(ServiceId local, ServiceId remote, RpcGateway rpc) {
            return createRemoteOwner(local, remote, rpc, PlayerBusinessCommandGateway.DEFAULT_RESPONSE_TIMEOUT);
        }

        private static Fixture createRemoteOwner(
                ServiceId local,
                ServiceId remote,
                RpcGateway rpc,
                Duration timeout
        ) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            ActorRef remoteRef = actors.actor("remote-player-10001");
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            directory.claim(AgentIdentity.player(10001L), new AgentLocation(remote, remoteRef));
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, rpc);
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            sessions.bind(10001L, "session-1");
            PlayerCommandSequencer sequencer = new PlayerCommandSequencer();
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    sequencer,
                    new AdmissionControlledAgentRouter(
                            (target, operation) -> AdmissionDecision.accept(),
                            new LifecycleAwareAgentRouter(lifecycles, messages)
                    ),
                    new InMemoryPlayerCommandAuditLog(),
                    ignored -> 1,
                    CLOCK
            );
            PlayerBusinessCommandBinder.registerExamples(dispatcher, (context, command) -> {
            });
            PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub();
            return new Fixture(executor, actors, lifecycles, responses, sequencer, dispatcher, null, directory,
                    new PlayerBusinessCommandGateway(
                            dispatcher,
                            rpc,
                            responses,
                            timeout,
                            new AgentDirectoryPlayerBusinessRedirectHandler(directory)
                    ));
        }
    }

    private static ActivityService activityService() {
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "kill-3",
                ActivityType.COUNTER,
                3,
                Reward.of(new ItemStack("gem", 5))
        ));
        activities.register(new ActivityDefinition(
                "battle-win-1",
                ActivityType.COUNTER,
                1,
                Reward.of(new ItemStack("gem", 2))
        ));
        return new ActivityService(activities, bagService());
    }

    private static GrowthService growthService() {
        return new GrowthService(bagService(), "exp_potion", 60, 100);
    }

    private static ShopService shopService() {
        ShopCatalog shops = new ShopCatalog();
        shops.register(new ShopItemDefinition(
                "growth_pack",
                new ItemStack("gold", 30),
                Reward.of(new ItemStack("exp_potion", 2)),
                0,
                0,
                ShopItemDefinition.UNLIMITED_STOCK
        ));
        return new ShopService(shops, bagService(), CLOCK, ZoneOffset.UTC);
    }

    private static BattleService battleService() {
        BattleStageCatalog battles = new BattleStageCatalog();
        battles.register(new BattleStageDefinition(
                "forest-1",
                100,
                40,
                70,
                8,
                5,
                Reward.of(new ItemStack("gold", 30), new ItemStack("exp_potion", 1)),
                "battle-win-1",
                1,
                Reward.of(new ItemStack("gem", 5)),
                3,
                5
        ));
        return new BattleService(battles, bagService(), activityService());
    }

    private static BagService bagService() {
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gold", "currency", 999999));
        items.register(new ItemDefinition("gem", "currency", 999999));
        items.register(new ItemDefinition("exp_potion", "growth", 999));
        return new BagService(items);
    }

    private static final class RecordingCallback implements RpcCallback<PlayerBusinessResponse> {
        final AtomicReference<PlayerBusinessResponse> response = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void success(PlayerBusinessResponse response) {
            this.response.set(response);
        }

        @Override
        public void failure(Throwable error) {
            failure.set(error);
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

        int queued() {
            return commands.size();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static final class SerializingClusterTransport implements ClusterTransport {
        private final LocalClusterTransport delegate = new LocalClusterTransport();
        private final ProtoClusterCodec codec;
        private int encodedMessages;

        private SerializingClusterTransport(PayloadCodecRegistry registry) {
            this.codec = new ProtoClusterCodec(registry);
        }

        @Override
        public void bind(ServiceDescriptor local, ClusterMessageHandler handler) {
            delegate.bind(local, handler);
        }

        @Override
        public void send(ServiceId nextHop, ClusterEnvelope envelope) {
            encodedMessages++;
            delegate.send(nextHop, codec.decode(codec.encode(envelope)));
        }

        int encodedMessages() {
            return encodedMessages;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class DroppingFirstRpcSuccessTransport implements ClusterTransport {
        private final LocalClusterTransport delegate = new LocalClusterTransport();
        private boolean dropped;

        @Override
        public void bind(ServiceDescriptor local, ClusterMessageHandler handler) {
            delegate.bind(local, handler);
        }

        @Override
        public void send(ServiceId nextHop, ClusterEnvelope envelope) {
            if (!dropped && "$rpc.success".equals(envelope.operation())) {
                dropped = true;
                return;
            }
            delegate.send(nextHop, envelope);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class FailingRpcGateway implements RpcGateway {
        private final FailureFactory failureFactory;

        private FailingRpcGateway(FailureFactory failureFactory) {
            this.failureFactory = failureFactory;
        }

        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            callback.failure(failureFactory.create(request));
        }
    }

    @FunctionalInterface
    private interface FailureFactory {
        Throwable create(RpcRequest<?> request);
    }

    private static final class HangingRpcGateway implements RpcGateway {
        private RpcCallback<PlayerBusinessResponse> callback;

        @Override
        @SuppressWarnings("unchecked")
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            this.callback = (RpcCallback<PlayerBusinessResponse>) callback;
        }

        void succeed(PlayerBusinessResponse response) {
            callback.success(response);
        }
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
