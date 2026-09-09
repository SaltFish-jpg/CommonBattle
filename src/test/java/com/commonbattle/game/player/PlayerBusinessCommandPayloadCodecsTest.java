package com.commonbattle.game.player;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import com.commonbattle.game.session.PlayerCommand;
import org.junit.jupiter.api.Test;

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
        assertEquals(null, envelope.payload());
    }
}
