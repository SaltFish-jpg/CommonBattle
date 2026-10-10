package com.commonbattle.game.player;

import com.commonbattle.battle.log.BattleLogEntry;
import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import com.commonbattle.game.bag.BagChange;
import com.commonbattle.game.bag.BagResult;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.battle.BattleSettlementStatus;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopPurchaseStatus;
import com.commonbattle.game.session.PlayerCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PlayerBusinessCommandPayloadCodecsTest {
    @Test
    void encodesAndDecodesActivityProgressCommand() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ActivityProgressCommand command = new ActivityProgressCommand("kill-3", 3);

        EncodedPayload encoded = registry.encode(command);
        ActivityProgressCommand decoded = (ActivityProgressCommand) registry.decode(
                encoded.codecName(),
                encoded.typeName(),
                encoded.bytes()
        );

        assertEquals(PayloadEncoding.PROTOBUF, encoded.codecName());
        assertEquals(PlayerBusinessOperations.ACTIVITY_PROGRESS, decoded.operation());
        assertEquals(command, decoded);
    }

    @Test
    void encodesAndDecodesPlayerCommandEnvelopeWithBusinessPayload() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        PlayerCommand command = new PlayerCommand(
                10001L,
                "session-1",
                1,
                8,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 3)
        );

        EncodedPayload encoded = registry.encode(command);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        PlayerCommand envelope = assertInstanceOf(PlayerCommand.class, decoded);
        assertEquals(PayloadEncoding.PROTOBUF, encoded.codecName());
        assertEquals(command.playerId(), envelope.playerId());
        assertEquals(command.sessionId(), envelope.sessionId());
        assertEquals(command.sessionEpoch(), envelope.sessionEpoch());
        assertEquals(command.sequence(), envelope.sequence());
        assertEquals(command.operation(), envelope.operation());
        assertEquals(command.payload(), envelope.payload());
    }

    @Test
    void encodesAndDecodesClaimActivityCommand() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ClaimActivityCommand command = new ClaimActivityCommand("daily-login");

        EncodedPayload encoded = registry.encode(command);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        ClaimActivityCommand claim = assertInstanceOf(ClaimActivityCommand.class, decoded);
        assertEquals(PlayerBusinessOperations.ACTIVITY_CLAIM, claim.operation());
        assertEquals(command, claim);
    }

    @Test
    void encodesAndDecodesUseExpItemsCommand() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        UseExpItemsCommand command = new UseExpItemsCommand(2);

        EncodedPayload encoded = registry.encode(command);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        UseExpItemsCommand use = assertInstanceOf(UseExpItemsCommand.class, decoded);
        assertEquals(PlayerBusinessOperations.GROWTH_USE_EXP_ITEMS, use.operation());
        assertEquals(command, use);
    }

    @Test
    void encodesAndDecodesConsumeBagItemCommand() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ConsumeBagItemCommand command = new ConsumeBagItemCommand("stamina_potion", 2);

        EncodedPayload encoded = registry.encode(command);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        ConsumeBagItemCommand consume = assertInstanceOf(ConsumeBagItemCommand.class, decoded);
        assertEquals(PlayerBusinessOperations.BAG_CONSUME_ITEM, consume.operation());
        assertEquals(command, consume);
    }

    @Test
    void encodesAndDecodesBuyShopItemCommand() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        BuyShopItemCommand command = new BuyShopItemCommand("order-10001-1", "growth_pack", 1);

        EncodedPayload encoded = registry.encode(command);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        BuyShopItemCommand buy = assertInstanceOf(BuyShopItemCommand.class, decoded);
        assertEquals(PlayerBusinessOperations.SHOP_BUY, buy.operation());
        assertEquals(command, buy);
    }

    @Test
    void encodesAndDecodesBuyShopItemAsyncCommand() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        BuyShopItemAsyncCommand command = new BuyShopItemAsyncCommand("order-10001-1", "limited_pack", 1);

        EncodedPayload encoded = registry.encode(command);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        BuyShopItemAsyncCommand buy = assertInstanceOf(BuyShopItemAsyncCommand.class, decoded);
        assertEquals(PlayerBusinessOperations.SHOP_BUY, buy.operation());
        assertEquals(command, buy);
    }

    @Test
    void encodesAndDecodesBattleStageClearCommand() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        BattleStageClearCommand command = new BattleStageClearCommand("settle-10001-1", "forest-1");

        EncodedPayload encoded = registry.encode(command);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        BattleStageClearCommand battle = assertInstanceOf(BattleStageClearCommand.class, decoded);
        assertEquals(PlayerBusinessOperations.BATTLE_CLEAR_STAGE, battle.operation());
        assertEquals(command, battle);
    }

    @Test
    void encodesAndDecodesBattleStageSweepCommand() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        BattleStageSweepCommand command = new BattleStageSweepCommand("sweep-10001-1", "forest-1");

        EncodedPayload encoded = registry.encode(command);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        BattleStageSweepCommand sweep = assertInstanceOf(BattleStageSweepCommand.class, decoded);
        assertEquals(PlayerBusinessOperations.BATTLE_SWEEP_STAGE, sweep.operation());
        assertEquals(command, sweep);
    }

    @Test
    void encodesAndDecodesClaimTaskCommand() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ClaimTaskCommand command = new ClaimTaskCommand("task-clear-forest");

        EncodedPayload encoded = registry.encode(command);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        ClaimTaskCommand claim = assertInstanceOf(ClaimTaskCommand.class, decoded);
        assertEquals(PlayerBusinessOperations.TASK_CLAIM, claim.operation());
        assertEquals(command, claim);
    }

    @Test
    void encodesAndDecodesClaimAchievementCommand() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ClaimAchievementCommand command = new ClaimAchievementCommand("achievement-clear-forest");

        EncodedPayload encoded = registry.encode(command);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        ClaimAchievementCommand claim = assertInstanceOf(ClaimAchievementCommand.class, decoded);
        assertEquals(PlayerBusinessOperations.ACHIEVEMENT_CLAIM, claim.operation());
        assertEquals(command, claim);
    }

    @Test
    void encodesAndDecodesSuccessfulBusinessResponseEnvelope() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        PlayerBusinessResponse response = new PlayerBusinessResponse(
                10001L,
                "session-1",
                1,
                9,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                PlayerBusinessResponseStatus.SUCCESS,
                PlayerBusinessResponse.OK,
                "",
                PlayerBusinessAck.OK
        );

        EncodedPayload encoded = registry.encode(response);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        PlayerBusinessResponse envelope = assertInstanceOf(PlayerBusinessResponse.class, decoded);
        assertEquals(PayloadEncoding.PROTOBUF, encoded.codecName());
        assertEquals(response.playerId(), envelope.playerId());
        assertEquals(response.sessionId(), envelope.sessionId());
        assertEquals(response.sessionEpoch(), envelope.sessionEpoch());
        assertEquals(response.sequence(), envelope.sequence());
        assertEquals(response.operation(), envelope.operation());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, envelope.status());
        assertEquals(PlayerBusinessResponse.OK, envelope.code());
        assertEquals("", envelope.message());
        assertEquals(PlayerBusinessAck.OK, envelope.payload());
    }

    @Test
    void encodesAndDecodesFailedBusinessResponseEnvelopeWithoutPayload() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        PlayerBusinessResponse response = new PlayerBusinessResponse(
                10001L,
                "session-1",
                1,
                10,
                PlayerBusinessOperations.SHOP_BUY,
                PlayerBusinessResponseStatus.FAILED,
                PlayerBusinessResponse.BUSINESS_REJECTED,
                "stock not enough",
                250,
                null
        );

        EncodedPayload encoded = registry.encode(response);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        PlayerBusinessResponse envelope = assertInstanceOf(PlayerBusinessResponse.class, decoded);
        assertEquals(response.playerId(), envelope.playerId());
        assertEquals(response.sessionId(), envelope.sessionId());
        assertEquals(response.sessionEpoch(), envelope.sessionEpoch());
        assertEquals(response.sequence(), envelope.sequence());
        assertEquals(response.operation(), envelope.operation());
        assertEquals(PlayerBusinessResponseStatus.FAILED, envelope.status());
        assertEquals(PlayerBusinessResponse.BUSINESS_REJECTED, envelope.code());
        assertEquals("stock not enough", envelope.message());
        assertEquals(250, envelope.retryAfterMillis());
        assertEquals(null, envelope.payload());
    }

    @Test
    void encodesAndDecodesBusinessResponseEnvelopeWithShopResultPayload() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ShopPurchaseResult result = new ShopPurchaseResult(
                ShopPurchaseStatus.SUCCESS,
                "growth_pack",
                1,
                new BagResult(List.of(new BagChange("gold", 100, 70))),
                new BagResult(List.of(new BagChange("exp_potion", 0, 2))),
                3,
                1,
                false
        );
        PlayerBusinessResponse response = new PlayerBusinessResponse(
                10001L,
                "session-1",
                1,
                11,
                PlayerBusinessOperations.SHOP_BUY,
                PlayerBusinessResponseStatus.SUCCESS,
                PlayerBusinessResponse.OK,
                "",
                result
        );

        EncodedPayload encoded = registry.encode(response);
        PlayerBusinessResponse decoded = (PlayerBusinessResponse) registry.decode(
                encoded.codecName(), encoded.typeName(), encoded.bytes());

        assertEquals(PayloadEncoding.PROTOBUF, encoded.codecName());
        assertEquals(result, decoded.payload());
    }

    @Test
    void encodesAndDecodesBattleSettlementResultPayload() {
        PayloadCodecRegistry registry = PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        BattleSettlementResult result = new BattleSettlementResult(
                "settle-1",
                "forest-1",
                BattleSettlementStatus.VICTORY,
                3,
                90,
                0,
                new BagResult(List.of(new BagChange("gold", 10, 40))),
                "kill-3",
                1,
                3,
                true,
                1,
                List.of(new BattleLogEntry(1, Instant.parse("2026-09-01T00:00:00Z"), "round", "hit")),
                false,
                false
        );

        EncodedPayload encoded = registry.encode(result);
        Object decoded = registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());

        assertEquals(PayloadEncoding.PROTOBUF, encoded.codecName());
        assertEquals(result, decoded);
    }
}
