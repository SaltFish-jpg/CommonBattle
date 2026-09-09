package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.ProtoClusterCodec;
import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivitySchedule;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.activity.ParticipationCondition;
import com.commonbattle.game.achievement.AchievementDefinition;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.battle.BattleStageDefinition;
import com.commonbattle.game.config.GameConfigChangeType;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigPackage;
import com.commonbattle.game.config.GameConfigOperations;
import com.commonbattle.game.config.GameConfigSnapshot;
import com.commonbattle.game.config.GameConfigSnapshotRequest;
import com.commonbattle.game.config.GrowthTuning;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.game.profile.ProfileSnapshotOperations;
import com.commonbattle.game.profile.ProfileSnapshotRequest;
import com.commonbattle.game.profile.ProfileSnapshotResponse;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.EventProgressRule;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.shop.ShopItemDefinition;
import com.commonbattle.game.social.AllianceSnapshot;
import com.commonbattle.game.social.AllianceSnapshotOperations;
import com.commonbattle.game.social.AllianceSnapshotRequest;
import com.commonbattle.game.social.AllianceSnapshotResponse;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendRelationAction;
import com.commonbattle.game.social.FriendSnapshot;
import com.commonbattle.game.social.FriendSnapshotOperations;
import com.commonbattle.game.social.FriendSnapshotRequest;
import com.commonbattle.game.social.FriendSnapshotResponse;
import com.commonbattle.game.task.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ClusterEventPayloadCodecsTest {
    @Test
    void eventPublishRequestCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        ProfileChangedEvent event = new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        20,
                        new AppearanceSummary("avatar_2", "frame_1", "costume_9"),
                        new AllianceBrief(100, "alliance", "badge"),
                        new FriendBrief(9, 3),
                        7,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
        ClusterEnvelope envelope = new ClusterEnvelope(
                1,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                ClusterEventOperations.PUBLISH,
                new EventPublishRequest(event)
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        EventPublishRequest request = assertInstanceOf(EventPublishRequest.class, decoded.payload());
        ProfileChangedEvent decodedEvent = assertInstanceOf(ProfileChangedEvent.class, request.event());
        assertEquals(7, decodedEvent.revision());
        assertEquals("avatar_2", decodedEvent.snapshot().appearance().avatar());
    }

    @Test
    void gameConfigChangedEventCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        GameConfigChangedEvent event = GameConfigChangedEvent.grayPublished(
                3,
                new GameConfigPackage(
                        12,
                        List.of(
                                new ItemDefinition("gold", "currency", 999999),
                                new ItemDefinition("exp_potion", "growth", 999)
                        ),
                        List.of(new ActivityDefinition(
                                "open-day-2",
                                ActivityType.COUNTER,
                                1,
                                Reward.of(new ItemStack("gold", 10)),
                                ActivitySchedule.openServerWindow(Duration.ofDays(1), Duration.ofDays(3)),
                                ParticipationCondition.minLevel(5),
                                EventProgressRule.of(BattleStageClearedEvent.TYPE, "forest-1")
                        )),
                        List.of(new ShopItemDefinition(
                                "growth_pack",
                                new ItemStack("gold", 50),
                                Reward.of(new ItemStack("exp_potion", 1)),
                                2,
                                1,
                                ShopItemDefinition.UNLIMITED_STOCK
                        )),
                        List.of(new BattleStageDefinition(
                                "forest-1",
                                100,
                                40,
                                70,
                                8,
                                5,
                                Reward.of(new ItemStack("gold", 30)),
                                "open-day-2",
                                1,
                                Reward.of(new ItemStack("exp_potion", 2)),
                                3
                        )),
                        List.of(new TaskDefinition(
                                "task-clear-forest",
                                BattleStageClearedEvent.TYPE,
                                "forest-1",
                                1,
                                Reward.of(new ItemStack("gold", 5))
                        )),
                        List.of(new AchievementDefinition(
                                "achievement-clear-forest",
                                BattleStageClearedEvent.TYPE,
                                "forest-1",
                                1,
                                Reward.of(new ItemStack("gold", 15))
                        )),
                        new GrowthTuning("exp_potion", 80, 100),
                        Instant.parse("2026-09-01T00:00:00Z")
                ),
                25
        );
        ClusterEnvelope envelope = new ClusterEnvelope(
                2,
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                ClusterEventOperations.DELIVER,
                new EventDeliverRequest(event)
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        EventDeliverRequest request = assertInstanceOf(EventDeliverRequest.class, decoded.payload());
        GameConfigChangedEvent decodedEvent = assertInstanceOf(GameConfigChangedEvent.class, request.event());
        assertEquals(3, decodedEvent.revision());
        assertEquals(GameConfigChangeType.GRAY_PUBLISHED, decodedEvent.changeType());
        assertEquals(12, decodedEvent.config().version());
        assertEquals(25, decodedEvent.grayPercent());
        assertEquals("open-day-2", decodedEvent.config().activities().getFirst().activityId());
        assertEquals(BattleStageClearedEvent.TYPE,
                decodedEvent.config().activities().getFirst().progressRule().eventType());
        assertEquals("growth_pack", decodedEvent.config().shops().getFirst().sku());
        assertEquals("forest-1", decodedEvent.config().battles().getFirst().stageId());
        assertEquals(2, decodedEvent.config().battles().getFirst().firstClearReward().items().getFirst().count());
        assertEquals(3, decodedEvent.config().battles().getFirst().sweepRequiredStars());
        assertEquals("task-clear-forest", decodedEvent.config().tasks().getFirst().taskId());
        assertEquals(BattleStageClearedEvent.TYPE,
                decodedEvent.config().tasks().getFirst().progressRule().eventType());
        assertEquals("achievement-clear-forest", decodedEvent.config().achievements().getFirst().achievementId());
        assertEquals(BattleStageClearedEvent.TYPE,
                decodedEvent.config().achievements().getFirst().progressRule().eventType());
    }

    @Test
    void playerDomainEventCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        PlayerDomainVersionedEvent event = new PlayerDomainVersionedEvent(
                10001L,
                BattleStageClearedEvent.TYPE,
                "forest-1",
                1,
                9,
                Instant.parse("2026-09-01T00:00:00Z")
        );
        ClusterEnvelope envelope = new ClusterEnvelope(
                21,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                ClusterEventOperations.PUBLISH,
                new EventPublishRequest(event)
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        EventPublishRequest request = assertInstanceOf(EventPublishRequest.class, decoded.payload());
        PlayerDomainVersionedEvent decodedEvent = assertInstanceOf(PlayerDomainVersionedEvent.class, request.event());
        assertEquals(PlayerDomainVersionedEvent.TOPIC, decodedEvent.topic());
        assertEquals(PlayerDomainVersionedEvent.ownerKey(10001L), decodedEvent.ownerKey());
        assertEquals(BattleStageClearedEvent.TYPE, decodedEvent.eventType());
        assertEquals("forest-1", decodedEvent.subject());
        assertEquals(9, decodedEvent.revision());
    }

    @Test
    void friendChangedEventCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        FriendChangedEvent event = new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 5);
        ClusterEnvelope envelope = new ClusterEnvelope(
                22,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                ClusterEventOperations.PUBLISH,
                new EventPublishRequest(event)
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        EventPublishRequest request = assertInstanceOf(EventPublishRequest.class, decoded.payload());
        FriendChangedEvent decodedEvent = assertInstanceOf(FriendChangedEvent.class, request.event());
        assertEquals(FriendChangedEvent.TOPIC, decodedEvent.topic());
        assertEquals("friend:10001", decodedEvent.ownerKey());
        assertEquals(20002L, decodedEvent.friendId());
        assertEquals(FriendRelationAction.ADD, decodedEvent.action());
        assertEquals(5, decodedEvent.revision());
    }

    @Test
    void gameConfigSnapshotRpcPayloadsCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        GameConfigPackage active = config(1, 60);
        GameConfigPackage gray = config(2, 120);
        ClusterEnvelope request = new ClusterEnvelope(
                3,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                GameConfigOperations.SNAPSHOT,
                new GameConfigSnapshotRequest(7)
        );
        ClusterEnvelope response = new ClusterEnvelope(
                3,
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                "$rpc.success",
                GameConfigSnapshot.withGray(8, active, gray, 50)
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        GameConfigSnapshotRequest requestPayload = assertInstanceOf(GameConfigSnapshotRequest.class, decodedRequest.payload());
        GameConfigSnapshot responsePayload = assertInstanceOf(GameConfigSnapshot.class, decodedResponse.payload());
        assertEquals(7, requestPayload.knownEventRevision());
        assertEquals(8, responsePayload.eventRevision());
        assertEquals(1, responsePayload.activeConfig().version());
        assertEquals(2, responsePayload.grayConfig().version());
        assertEquals(50, responsePayload.grayPercent());
    }

    @Test
    void replayPayloadsCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        ClusterEnvelope request = new ClusterEnvelope(
                4,
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                ClusterEventOperations.REPLAY,
                new EventReplayRequest(
                        ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                        ProfileChangedEvent.TOPIC,
                        Map.of("profile:10001", 7L),
                        Set.of("profile:10001")
                )
        );
        ClusterEnvelope response = new ClusterEnvelope(
                4,
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                "$rpc.success",
                new EventReplayResult(2, 1, Set.of("profile:10001"))
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        EventReplayRequest requestPayload = assertInstanceOf(EventReplayRequest.class, decodedRequest.payload());
        EventReplayResult responsePayload = assertInstanceOf(EventReplayResult.class, decodedResponse.payload());
        assertEquals(ProfileChangedEvent.TOPIC, requestPayload.topic());
        assertEquals(7L, requestPayload.knownRevisions().get("profile:10001"));
        assertEquals(Set.of("profile:10001"), requestPayload.ownerKeys());
        assertEquals(2, responsePayload.delivered());
        assertEquals(1, responsePayload.unavailableOwners());
        assertEquals(Set.of("profile:10001"), responsePayload.unavailableOwnerKeys());
    }

    @Test
    void subscriptionPayloadsCanCarryOwnerFilters() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        ClusterEnvelope envelope = new ClusterEnvelope(
                5,
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                ClusterEventOperations.SUBSCRIBE,
                new EventSubscribeRequest(
                        ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                        ProfileChangedEvent.TOPIC,
                        Set.of("profile:10001", "profile:10002"),
                        Duration.ofSeconds(12)
                )
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        EventSubscribeRequest requestPayload = assertInstanceOf(EventSubscribeRequest.class, decoded.payload());
        assertEquals(ProfileChangedEvent.TOPIC, requestPayload.topic());
        assertEquals(Set.of("profile:10001", "profile:10002"), requestPayload.ownerKeys());
        assertEquals(Duration.ofSeconds(12), requestPayload.leaseTtl());
    }

    @Test
    void profileSnapshotPayloadsCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        ClusterEnvelope request = new ClusterEnvelope(
                6,
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ProfileSnapshotOperations.GET,
                new ProfileSnapshotRequest(10001L)
        );
        ClusterEnvelope response = new ClusterEnvelope(
                6,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                "$rpc.success",
                ProfileSnapshotResponse.found(new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        20,
                        new AppearanceSummary("avatar_2", "frame_1", "costume_9"),
                        new AllianceBrief(100, "alliance", "badge"),
                        new FriendBrief(9, 3),
                        7,
                        Instant.parse("2026-09-01T00:00:00Z")
                ))
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        ProfileSnapshotRequest requestPayload = assertInstanceOf(ProfileSnapshotRequest.class, decodedRequest.payload());
        ProfileSnapshotResponse responsePayload = assertInstanceOf(ProfileSnapshotResponse.class, decodedResponse.payload());
        assertEquals(10001L, requestPayload.playerId());
        assertEquals(7, responsePayload.snapshot().revision());
        assertEquals("avatar_2", responsePayload.snapshot().appearance().avatar());
    }

    @Test
    void allianceSnapshotPayloadsCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        ClusterEnvelope request = new ClusterEnvelope(
                7,
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                AllianceSnapshotOperations.GET,
                new AllianceSnapshotRequest(100)
        );
        ClusterEnvelope response = new ClusterEnvelope(
                7,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                "$rpc.success",
                AllianceSnapshotResponse.found(new AllianceSnapshot(100, 3, Set.of(10001L, 10002L)))
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        AllianceSnapshotRequest requestPayload = assertInstanceOf(AllianceSnapshotRequest.class, decodedRequest.payload());
        AllianceSnapshotResponse responsePayload = assertInstanceOf(AllianceSnapshotResponse.class, decodedResponse.payload());
        assertEquals(100, requestPayload.allianceId());
        assertEquals(3, responsePayload.snapshot().revision());
        assertEquals(Set.of(10001L, 10002L), responsePayload.snapshot().members());
    }

    @Test
    void friendSnapshotPayloadsCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        ClusterEnvelope request = new ClusterEnvelope(
                8,
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                FriendSnapshotOperations.GET,
                new FriendSnapshotRequest(10001L)
        );
        ClusterEnvelope response = new ClusterEnvelope(
                8,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                "$rpc.success",
                FriendSnapshotResponse.found(new FriendSnapshot(10001L, 3, Set.of(20002L, 30003L)))
        );

        ClusterEnvelope decodedRequest = codec.decode(codec.encode(request));
        ClusterEnvelope decodedResponse = codec.decode(codec.encode(response));

        FriendSnapshotRequest requestPayload = assertInstanceOf(FriendSnapshotRequest.class, decodedRequest.payload());
        FriendSnapshotResponse responsePayload = assertInstanceOf(FriendSnapshotResponse.class, decodedResponse.payload());
        assertEquals(10001L, requestPayload.playerId());
        assertEquals(3, responsePayload.snapshot().revision());
        assertEquals(Set.of(20002L, 30003L), responsePayload.snapshot().friends());
    }

    private static GameConfigPackage config(long version, int expPerItem) {
        return new GameConfigPackage(
                version,
                List.of(
                        new ItemDefinition("gold", "currency", 999999),
                        new ItemDefinition("exp_potion", "growth", 999)
                ),
                List.of(new ActivityDefinition(
                        "daily-login",
                        ActivityType.LOGIN,
                        1,
                        Reward.of(new ItemStack("gold", 100), new ItemStack("exp_potion", 1))
                )),
                List.of(new ShopItemDefinition(
                        "growth_pack",
                        new ItemStack("gold", 50),
                        Reward.of(new ItemStack("exp_potion", 1)),
                        2,
                        1,
                        ShopItemDefinition.UNLIMITED_STOCK
                )),
                List.of(new BattleStageDefinition(
                        "forest-1",
                        100,
                        40,
                        70,
                        8,
                        5,
                        Reward.of(new ItemStack("gold", 30)),
                        "",
                        0
                )),
                List.of(new TaskDefinition(
                        "task-clear-forest",
                        BattleStageClearedEvent.TYPE,
                        "forest-1",
                        1,
                        Reward.of(new ItemStack("gold", 5))
                )),
                List.of(new AchievementDefinition(
                        "achievement-clear-forest",
                        BattleStageClearedEvent.TYPE,
                        "forest-1",
                        1,
                        Reward.of(new ItemStack("gold", 15))
                )),
                new GrowthTuning("exp_potion", expPerItem, 100),
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }
}
