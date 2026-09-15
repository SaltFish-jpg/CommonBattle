package com.commonbattle.game.player;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import com.commonbattle.game.activity.ActivityProgressSnapshot;
import com.commonbattle.game.activity.PlayerActivitiesSnapshot;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.battle.BattleSettlementSnapshot;
import com.commonbattle.game.battle.BattleSettlementStatus;
import com.commonbattle.game.battle.BattleStageProgressSnapshot;
import com.commonbattle.game.battle.PlayerBattleSnapshot;
import com.commonbattle.game.growth.GrowthSnapshot;
import com.commonbattle.game.session.PlayerClientEnvelope;
import com.commonbattle.game.session.ProtoPlayerClientCodec;
import com.commonbattle.game.shop.PlayerShopSnapshot;
import com.commonbattle.game.task.PlayerTasksSnapshot;
import com.commonbattle.game.task.TaskProgressSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PlayerPushPayloadCodecsTest {
    @Test
    void registersBusinessPushPayloadsAsProtostuffBeans() {
        PayloadCodecRegistry registry = PlayerPushPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());

        PlayerPushPayloads.BagSnapshotPayload bag = roundTrip(registry,
                PlayerPushPayloads.bag(new BagSnapshot(Map.of("gold", 100))));
        assertEquals(PayloadEncoding.PROTOSTUFF, registry.encode(bag).codecName());
        assertEquals(100, bag.itemCounts.get("gold"));

        PlayerPushPayloads.ActivityProgressPayload activity = roundTrip(registry,
                PlayerPushPayloads.activities(new PlayerActivitiesSnapshot(Map.of(
                        "daily-login", new ActivityProgressSnapshot(1, true)
                ))));
        assertEquals(1, activity.progress.get("daily-login").value);
        assertEquals(true, activity.progress.get("daily-login").claimed);

        PlayerPushPayloads.GrowthSnapshotPayload growth = roundTrip(registry,
                PlayerPushPayloads.growth(new GrowthSnapshot(3, 25)));
        assertEquals(3, growth.level);
        assertEquals(25, growth.exp);

        PlayerPushPayloads.ShopSnapshotPayload shop = roundTrip(registry,
                PlayerPushPayloads.shop(new PlayerShopSnapshot(Map.of("growth_pack", 2), Map.of("growth_pack", 1))));
        assertEquals(2, shop.lifetimePurchases.get("growth_pack"));
        assertEquals(1, shop.dailyPurchases.get("growth_pack"));
    }

    @Test
    void playerClientEnvelopeRoundTripsBusinessSnapshotPayload() {
        PayloadCodecRegistry registry = PlayerPushPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoPlayerClientCodec codec = new ProtoPlayerClientCodec(registry);
        PlayerPushPayloads.BattleSnapshotPayload battle = PlayerPushPayloads.battle(new PlayerBattleSnapshot(
                Map.of("settle-1", new BattleSettlementSnapshot(
                        "settle-1",
                        "forest-1",
                        BattleSettlementStatus.VICTORY,
                        3,
                        80,
                        0,
                        new com.commonbattle.game.bag.BagResult(java.util.List.of()),
                        "battle-win-1",
                        1,
                        3,
                        true,
                        1,
                        false
                )),
                Map.of("forest-1", new BattleStageProgressSnapshot(
                        "forest-1",
                        1,
                        3,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:01:00Z")
                ))
        ));

        PlayerClientEnvelope decoded = codec.decode(codec.encode(new PlayerClientEnvelope(
                10001L,
                "battle.snapshot",
                battle,
                7,
                Instant.parse("2026-09-01T00:02:00Z")
        )));

        PlayerPushPayloads.BattleSnapshotPayload payload =
                assertInstanceOf(PlayerPushPayloads.BattleSnapshotPayload.class, decoded.payload());
        assertEquals("VICTORY", payload.settlements.get("settle-1").status);
        assertEquals(3, payload.stages.get("forest-1").bestStars);
    }

    @Test
    void taskPayloadCanBeDecodedAfterProtostuffRoundTrip() {
        PayloadCodecRegistry registry = PlayerPushPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());

        PlayerPushPayloads.TaskProgressPayload tasks = roundTrip(registry,
                PlayerPushPayloads.tasks(new PlayerTasksSnapshot(Map.of(
                        "task-clear-forest", new TaskProgressSnapshot(1, false)
                ))));

        assertEquals(1, tasks.progress.get("task-clear-forest").value);
        assertEquals(false, tasks.progress.get("task-clear-forest").claimed);
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(PayloadCodecRegistry registry, T payload) {
        EncodedPayload encoded = registry.encode(payload);
        return (T) registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());
    }
}
