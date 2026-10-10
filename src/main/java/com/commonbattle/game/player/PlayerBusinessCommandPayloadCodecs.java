package com.commonbattle.game.player;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import com.commonbattle.battle.log.BattleLogEntry;
import com.commonbattle.game.achievement.AchievementClaimResult;
import com.commonbattle.game.activity.ActivityClaimResult;
import com.commonbattle.game.bag.BagChange;
import com.commonbattle.game.bag.BagResult;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.battle.BattleSettlementStatus;
import com.commonbattle.game.growth.GrowthResult;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.shop.ShopPurchaseStatus;
import com.commonbattle.game.task.TaskClaimResult;
import com.commonbattle.game.session.PlayerCommand;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 玩家业务命令 payload codec。
 * 字段号按业务参数尾后追加，避免灰度期间新旧进程因为未知字段互相破坏。
 */
public final class PlayerBusinessCommandPayloadCodecs {
    private PlayerBusinessCommandPayloadCodecs() {
    }

    public static PayloadCodecRegistry registerTo(PayloadCodecRegistry registry) {
        registry.register(new PlayerBusinessAckCodec());
        registry.register(new PlayerCommandCodec(registry));
        registry.register(new PlayerBusinessResponseCodec(registry));
        registry.register(new ConsumeBagItemCommandCodec());
        registry.register(new ActivityProgressCommandCodec());
        registry.register(new ClaimActivityCommandCodec());
        registry.register(new UseExpItemsCommandCodec());
        registry.register(new BuyShopItemCommandCodec());
        registry.register(new BuyShopItemAsyncCommandCodec());
        registry.register(new BattleStageClearCommandCodec());
        registry.register(new BattleStageSweepCommandCodec());
        registry.register(new ClaimTaskCommandCodec());
        registry.register(new ClaimAchievementCommandCodec());
        registry.register(new BagResultCodec());
        registry.register(new ActivityClaimResultCodec());
        registry.register(new GrowthResultCodec());
        registry.register(new ShopPurchaseResultCodec());
        registry.register(new BattleSettlementResultCodec());
        registry.register(new TaskClaimResultCodec());
        registry.register(new AchievementClaimResultCodec());
        return registry;
    }

    private static final class PlayerBusinessAckCodec implements PayloadCodec<PlayerBusinessAck> {
        @Override
        public String typeName() {
            return PlayerBusinessAck.class.getName();
        }

        @Override
        public Class<PlayerBusinessAck> javaType() {
            return PlayerBusinessAck.class;
        }

        @Override
        public byte[] encode(PlayerBusinessAck payload) {
            return encodeString(payload.name());
        }

        @Override
        public PlayerBusinessAck decode(byte[] bytes) {
            return PlayerBusinessAck.valueOf(decodeString(bytes));
        }
    }

    private static final class PlayerCommandCodec implements PayloadCodec<PlayerCommand> {
        private final PayloadCodecRegistry registry;

        private PlayerCommandCodec(PayloadCodecRegistry registry) {
            this.registry = registry;
        }

        @Override
        public String typeName() {
            return PlayerCommand.class.getName();
        }

        @Override
        public Class<PlayerCommand> javaType() {
            return PlayerCommand.class;
        }

        @Override
        public byte[] encode(PlayerCommand payload) {
            EncodedPayload nested = registry.encode(payload.payload());
            int size = CodedOutputStream.computeInt64Size(1, payload.playerId())
                    + CodedOutputStream.computeStringSize(2, payload.sessionId())
                    + CodedOutputStream.computeInt64Size(3, payload.sessionEpoch())
                    + CodedOutputStream.computeInt64Size(4, payload.sequence())
                    + CodedOutputStream.computeStringSize(5, payload.operation())
                    + CodedOutputStream.computeStringSize(6, nested.codecName())
                    + CodedOutputStream.computeStringSize(7, nested.typeName())
                    + CodedOutputStream.computeByteArraySize(8, nested.bytes());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.playerId());
                output.writeString(2, payload.sessionId());
                output.writeInt64(3, payload.sessionEpoch());
                output.writeInt64(4, payload.sequence());
                output.writeString(5, payload.operation());
                output.writeString(6, nested.codecName());
                output.writeString(7, nested.typeName());
                output.writeByteArray(8, nested.bytes());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player command", e);
            }
        }

        @Override
        public PlayerCommand decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 0;
            String sessionId = "";
            long sessionEpoch = 0;
            long sequence = 0;
            String operation = "";
            String payloadCodecName = PayloadEncoding.NONE;
            String payloadTypeName = "";
            byte[] payloadBytes = new byte[0];
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        case 2 -> sessionId = input.readString();
                        case 3 -> sessionEpoch = input.readInt64();
                        case 4 -> sequence = input.readInt64();
                        case 5 -> operation = input.readString();
                        case 6 -> payloadCodecName = input.readString();
                        case 7 -> payloadTypeName = input.readString();
                        case 8 -> payloadBytes = input.readByteArray();
                        default -> input.skipField(tag);
                    }
                }
                return new PlayerCommand(
                        playerId,
                        sessionId,
                        sessionEpoch,
                        sequence,
                        operation,
                        registry.decode(payloadCodecName, payloadTypeName, payloadBytes)
                );
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player command", e);
            }
        }
    }

    private static final class PlayerBusinessResponseCodec implements PayloadCodec<PlayerBusinessResponse> {
        private final PayloadCodecRegistry registry;

        private PlayerBusinessResponseCodec(PayloadCodecRegistry registry) {
            this.registry = registry;
        }

        @Override
        public String typeName() {
            return PlayerBusinessResponse.class.getName();
        }

        @Override
        public Class<PlayerBusinessResponse> javaType() {
            return PlayerBusinessResponse.class;
        }

        @Override
        public byte[] encode(PlayerBusinessResponse payload) {
            EncodedPayload nested = registry.encode(payload.payload());
            int size = CodedOutputStream.computeInt64Size(1, payload.playerId())
                    + CodedOutputStream.computeStringSize(2, payload.sessionId())
                    + CodedOutputStream.computeInt64Size(3, payload.sessionEpoch())
                    + CodedOutputStream.computeInt64Size(4, payload.sequence())
                    + CodedOutputStream.computeStringSize(5, payload.operation())
                    + CodedOutputStream.computeStringSize(6, payload.status().name())
                    + CodedOutputStream.computeStringSize(7, payload.code())
                    + CodedOutputStream.computeStringSize(8, payload.message())
                    + CodedOutputStream.computeStringSize(9, nested.codecName())
                    + CodedOutputStream.computeStringSize(10, nested.typeName())
                    + CodedOutputStream.computeByteArraySize(11, nested.bytes())
                    + CodedOutputStream.computeInt64Size(12, payload.retryAfterMillis());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt64(1, payload.playerId());
                output.writeString(2, payload.sessionId());
                output.writeInt64(3, payload.sessionEpoch());
                output.writeInt64(4, payload.sequence());
                output.writeString(5, payload.operation());
                output.writeString(6, payload.status().name());
                output.writeString(7, payload.code());
                output.writeString(8, payload.message());
                output.writeString(9, nested.codecName());
                output.writeString(10, nested.typeName());
                output.writeByteArray(11, nested.bytes());
                output.writeInt64(12, payload.retryAfterMillis());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player business response", e);
            }
        }

        @Override
        public PlayerBusinessResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            long playerId = 0;
            String sessionId = "";
            long sessionEpoch = 0;
            long sequence = 0;
            String operation = "";
            PlayerBusinessResponseStatus status = PlayerBusinessResponseStatus.SUCCESS;
            String code = PlayerBusinessResponse.OK;
            String message = "";
            String payloadCodecName = PayloadEncoding.NONE;
            String payloadTypeName = "";
            byte[] payloadBytes = new byte[0];
            long retryAfterMillis = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> playerId = input.readInt64();
                        case 2 -> sessionId = input.readString();
                        case 3 -> sessionEpoch = input.readInt64();
                        case 4 -> sequence = input.readInt64();
                        case 5 -> operation = input.readString();
                        case 6 -> status = PlayerBusinessResponseStatus.valueOf(input.readString());
                        case 7 -> code = input.readString();
                        case 8 -> message = input.readString();
                        case 9 -> payloadCodecName = input.readString();
                        case 10 -> payloadTypeName = input.readString();
                        case 11 -> payloadBytes = input.readByteArray();
                        case 12 -> retryAfterMillis = input.readInt64();
                        default -> input.skipField(tag);
                    }
                }
                Object responsePayload = registry.decode(payloadCodecName, payloadTypeName, payloadBytes);
                return new PlayerBusinessResponse(
                        playerId,
                        sessionId,
                        sessionEpoch,
                        sequence,
                        operation,
                        status,
                        code,
                        message,
                        retryAfterMillis,
                        responsePayload
                );
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player business response", e);
            }
        }
    }

    private static final class ConsumeBagItemCommandCodec implements PayloadCodec<ConsumeBagItemCommand> {
        @Override
        public String typeName() {
            return ConsumeBagItemCommand.class.getName();
        }

        @Override
        public Class<ConsumeBagItemCommand> javaType() {
            return ConsumeBagItemCommand.class;
        }

        @Override
        public byte[] encode(ConsumeBagItemCommand payload) {
            return encodeStringInt(payload.itemId(), payload.count());
        }

        @Override
        public ConsumeBagItemCommand decode(byte[] bytes) {
            StringInt decoded = decodeStringInt(bytes);
            return new ConsumeBagItemCommand(decoded.text(), decoded.value());
        }
    }

    private static final class ActivityProgressCommandCodec implements PayloadCodec<ActivityProgressCommand> {
        @Override
        public String typeName() {
            return ActivityProgressCommand.class.getName();
        }

        @Override
        public Class<ActivityProgressCommand> javaType() {
            return ActivityProgressCommand.class;
        }

        @Override
        public byte[] encode(ActivityProgressCommand payload) {
            return encodeStringInt(payload.activityId(), payload.delta());
        }

        @Override
        public ActivityProgressCommand decode(byte[] bytes) {
            StringInt decoded = decodeStringInt(bytes);
            return new ActivityProgressCommand(decoded.text(), decoded.value());
        }
    }

    private static final class ClaimActivityCommandCodec implements PayloadCodec<ClaimActivityCommand> {
        @Override
        public String typeName() {
            return ClaimActivityCommand.class.getName();
        }

        @Override
        public Class<ClaimActivityCommand> javaType() {
            return ClaimActivityCommand.class;
        }

        @Override
        public byte[] encode(ClaimActivityCommand payload) {
            return encodeString(payload.activityId());
        }

        @Override
        public ClaimActivityCommand decode(byte[] bytes) {
            return new ClaimActivityCommand(decodeString(bytes));
        }
    }

    private static final class UseExpItemsCommandCodec implements PayloadCodec<UseExpItemsCommand> {
        @Override
        public String typeName() {
            return UseExpItemsCommand.class.getName();
        }

        @Override
        public Class<UseExpItemsCommand> javaType() {
            return UseExpItemsCommand.class;
        }

        @Override
        public byte[] encode(UseExpItemsCommand payload) {
            return encodeInt(payload.count());
        }

        @Override
        public UseExpItemsCommand decode(byte[] bytes) {
            return new UseExpItemsCommand(decodeInt(bytes));
        }
    }

    private static final class BuyShopItemCommandCodec implements PayloadCodec<BuyShopItemCommand> {
        @Override
        public String typeName() {
            return BuyShopItemCommand.class.getName();
        }

        @Override
        public Class<BuyShopItemCommand> javaType() {
            return BuyShopItemCommand.class;
        }

        @Override
        public byte[] encode(BuyShopItemCommand payload) {
            int size = CodedOutputStream.computeStringSize(1, payload.sku())
                    + CodedOutputStream.computeInt32Size(2, payload.quantity())
                    + CodedOutputStream.computeStringSize(3, payload.orderId());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.sku());
                output.writeInt32(2, payload.quantity());
                output.writeString(3, payload.orderId());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player business command", e);
            }
        }

        @Override
        public BuyShopItemCommand decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            String sku = "";
            int quantity = 0;
            String orderId = "";
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> sku = input.readString();
                        case 2 -> quantity = input.readInt32();
                        case 3 -> orderId = input.readString();
                        default -> input.skipField(tag);
                    }
                }
                return new BuyShopItemCommand(orderId, sku, quantity);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player business command", e);
            }
        }
    }

    private static final class BuyShopItemAsyncCommandCodec implements PayloadCodec<BuyShopItemAsyncCommand> {
        @Override
        public String typeName() {
            return BuyShopItemAsyncCommand.class.getName();
        }

        @Override
        public Class<BuyShopItemAsyncCommand> javaType() {
            return BuyShopItemAsyncCommand.class;
        }

        @Override
        public byte[] encode(BuyShopItemAsyncCommand payload) {
            int size = CodedOutputStream.computeStringSize(1, payload.sku())
                    + CodedOutputStream.computeInt32Size(2, payload.quantity())
                    + CodedOutputStream.computeStringSize(3, payload.orderId());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.sku());
                output.writeInt32(2, payload.quantity());
                output.writeString(3, payload.orderId());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player business command", e);
            }
        }

        @Override
        public BuyShopItemAsyncCommand decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            String sku = "";
            int quantity = 0;
            String orderId = "";
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> sku = input.readString();
                        case 2 -> quantity = input.readInt32();
                        case 3 -> orderId = input.readString();
                        default -> input.skipField(tag);
                    }
                }
                return new BuyShopItemAsyncCommand(orderId, sku, quantity);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player business command", e);
            }
        }
    }

    private static final class BattleStageClearCommandCodec implements PayloadCodec<BattleStageClearCommand> {
        @Override
        public String typeName() {
            return BattleStageClearCommand.class.getName();
        }

        @Override
        public Class<BattleStageClearCommand> javaType() {
            return BattleStageClearCommand.class;
        }

        @Override
        public byte[] encode(BattleStageClearCommand payload) {
            return encodeBattleStageCommand(payload.stageId(), payload.settlementId());
        }

        @Override
        public BattleStageClearCommand decode(byte[] bytes) {
            StringString decoded = decodeBattleStageCommand(bytes);
            return new BattleStageClearCommand(decoded.second(), decoded.first());
        }
    }

    private static final class BattleStageSweepCommandCodec implements PayloadCodec<BattleStageSweepCommand> {
        @Override
        public String typeName() {
            return BattleStageSweepCommand.class.getName();
        }

        @Override
        public Class<BattleStageSweepCommand> javaType() {
            return BattleStageSweepCommand.class;
        }

        @Override
        public byte[] encode(BattleStageSweepCommand payload) {
            return encodeBattleStageCommand(payload.stageId(), payload.settlementId());
        }

        @Override
        public BattleStageSweepCommand decode(byte[] bytes) {
            StringString decoded = decodeBattleStageCommand(bytes);
            return new BattleStageSweepCommand(decoded.second(), decoded.first());
        }
    }

    private static final class ClaimTaskCommandCodec implements PayloadCodec<ClaimTaskCommand> {
        @Override
        public String typeName() {
            return ClaimTaskCommand.class.getName();
        }

        @Override
        public Class<ClaimTaskCommand> javaType() {
            return ClaimTaskCommand.class;
        }

        @Override
        public byte[] encode(ClaimTaskCommand payload) {
            return encodeString(payload.taskId());
        }

        @Override
        public ClaimTaskCommand decode(byte[] bytes) {
            return new ClaimTaskCommand(decodeString(bytes));
        }
    }

    private static final class ClaimAchievementCommandCodec implements PayloadCodec<ClaimAchievementCommand> {
        @Override
        public String typeName() {
            return ClaimAchievementCommand.class.getName();
        }

        @Override
        public Class<ClaimAchievementCommand> javaType() {
            return ClaimAchievementCommand.class;
        }

        @Override
        public byte[] encode(ClaimAchievementCommand payload) {
            return encodeString(payload.achievementId());
        }

        @Override
        public ClaimAchievementCommand decode(byte[] bytes) {
            return new ClaimAchievementCommand(decodeString(bytes));
        }
    }

    private static final class BagResultCodec implements PayloadCodec<BagResult> {
        @Override
        public String typeName() {
            return BagResult.class.getName();
        }

        @Override
        public Class<BagResult> javaType() {
            return BagResult.class;
        }

        @Override
        public byte[] encode(BagResult payload) {
            return encodeBagResult(payload);
        }

        @Override
        public BagResult decode(byte[] bytes) {
            return decodeBagResult(bytes);
        }
    }

    private static final class ActivityClaimResultCodec implements PayloadCodec<ActivityClaimResult> {
        @Override
        public String typeName() {
            return ActivityClaimResult.class.getName();
        }

        @Override
        public Class<ActivityClaimResult> javaType() {
            return ActivityClaimResult.class;
        }

        @Override
        public byte[] encode(ActivityClaimResult payload) {
            byte[] bag = encodeBagResult(payload.bagResult());
            int size = CodedOutputStream.computeStringSize(1, payload.activityId())
                    + CodedOutputStream.computeInt32Size(2, payload.progress())
                    + CodedOutputStream.computeByteArraySize(3, bag);
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.activityId());
                output.writeInt32(2, payload.progress());
                output.writeByteArray(3, bag);
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player business result", e);
            }
        }

        @Override
        public ActivityClaimResult decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            String activityId = "";
            int progress = 0;
            BagResult bag = new BagResult(List.of());
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> activityId = input.readString();
                        case 2 -> progress = input.readInt32();
                        case 3 -> bag = decodeBagResult(input.readByteArray());
                        default -> input.skipField(tag);
                    }
                }
                return new ActivityClaimResult(activityId, progress, bag);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player business result", e);
            }
        }
    }

    private static final class GrowthResultCodec implements PayloadCodec<GrowthResult> {
        @Override
        public String typeName() {
            return GrowthResult.class.getName();
        }

        @Override
        public Class<GrowthResult> javaType() {
            return GrowthResult.class;
        }

        @Override
        public byte[] encode(GrowthResult payload) {
            int size = CodedOutputStream.computeInt32Size(1, payload.beforeLevel())
                    + CodedOutputStream.computeInt32Size(2, payload.afterLevel())
                    + CodedOutputStream.computeInt32Size(3, payload.beforeExp())
                    + CodedOutputStream.computeInt32Size(4, payload.afterExp());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeInt32(1, payload.beforeLevel());
                output.writeInt32(2, payload.afterLevel());
                output.writeInt32(3, payload.beforeExp());
                output.writeInt32(4, payload.afterExp());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player business result", e);
            }
        }

        @Override
        public GrowthResult decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            int beforeLevel = 0;
            int afterLevel = 0;
            int beforeExp = 0;
            int afterExp = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> beforeLevel = input.readInt32();
                        case 2 -> afterLevel = input.readInt32();
                        case 3 -> beforeExp = input.readInt32();
                        case 4 -> afterExp = input.readInt32();
                        default -> input.skipField(tag);
                    }
                }
                return new GrowthResult(beforeLevel, afterLevel, beforeExp, afterExp);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player business result", e);
            }
        }
    }

    private static final class ShopPurchaseResultCodec implements PayloadCodec<ShopPurchaseResult> {
        @Override
        public String typeName() {
            return ShopPurchaseResult.class.getName();
        }

        @Override
        public Class<ShopPurchaseResult> javaType() {
            return ShopPurchaseResult.class;
        }

        @Override
        public byte[] encode(ShopPurchaseResult payload) {
            byte[] cost = encodeBagResult(payload.cost());
            byte[] reward = encodeBagResult(payload.reward());
            int size = CodedOutputStream.computeStringSize(1, payload.status().name())
                    + CodedOutputStream.computeStringSize(2, payload.sku())
                    + CodedOutputStream.computeInt32Size(3, payload.quantity())
                    + CodedOutputStream.computeByteArraySize(4, cost)
                    + CodedOutputStream.computeByteArraySize(5, reward)
                    + CodedOutputStream.computeInt32Size(6, payload.lifetimePurchased())
                    + CodedOutputStream.computeInt32Size(7, payload.dailyPurchased())
                    + CodedOutputStream.computeBoolSize(8, payload.replayed());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.status().name());
                output.writeString(2, payload.sku());
                output.writeInt32(3, payload.quantity());
                output.writeByteArray(4, cost);
                output.writeByteArray(5, reward);
                output.writeInt32(6, payload.lifetimePurchased());
                output.writeInt32(7, payload.dailyPurchased());
                output.writeBool(8, payload.replayed());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player business result", e);
            }
        }

        @Override
        public ShopPurchaseResult decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            ShopPurchaseStatus status = ShopPurchaseStatus.SUCCESS;
            String sku = "";
            int quantity = 1;
            BagResult cost = new BagResult(List.of());
            BagResult reward = new BagResult(List.of());
            int lifetimePurchased = 0;
            int dailyPurchased = 0;
            boolean replayed = false;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> status = ShopPurchaseStatus.valueOf(input.readString());
                        case 2 -> sku = input.readString();
                        case 3 -> quantity = input.readInt32();
                        case 4 -> cost = decodeBagResult(input.readByteArray());
                        case 5 -> reward = decodeBagResult(input.readByteArray());
                        case 6 -> lifetimePurchased = input.readInt32();
                        case 7 -> dailyPurchased = input.readInt32();
                        case 8 -> replayed = input.readBool();
                        default -> input.skipField(tag);
                    }
                }
                return new ShopPurchaseResult(status, sku, quantity, cost, reward,
                        lifetimePurchased, dailyPurchased, replayed);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player business result", e);
            }
        }
    }

    private static final class BattleSettlementResultCodec implements PayloadCodec<BattleSettlementResult> {
        @Override
        public String typeName() {
            return BattleSettlementResult.class.getName();
        }

        @Override
        public Class<BattleSettlementResult> javaType() {
            return BattleSettlementResult.class;
        }

        @Override
        public byte[] encode(BattleSettlementResult payload) {
            byte[] reward = encodeBagResult(payload.rewardResult());
            List<byte[]> logs = payload.log().stream().map(PlayerBusinessCommandPayloadCodecs::encodeBattleLogEntry).toList();
            int size = CodedOutputStream.computeStringSize(1, payload.settlementId())
                    + CodedOutputStream.computeStringSize(2, payload.stageId())
                    + CodedOutputStream.computeStringSize(3, payload.status().name())
                    + CodedOutputStream.computeInt32Size(4, payload.rounds())
                    + CodedOutputStream.computeInt32Size(5, payload.playerHp())
                    + CodedOutputStream.computeInt32Size(6, payload.enemyHp())
                    + CodedOutputStream.computeByteArraySize(7, reward)
                    + CodedOutputStream.computeStringSize(8, payload.progressActivityId())
                    + CodedOutputStream.computeInt32Size(9, payload.progressDelta())
                    + CodedOutputStream.computeInt32Size(10, payload.stars())
                    + CodedOutputStream.computeBoolSize(11, payload.firstClear())
                    + CodedOutputStream.computeInt32Size(12, payload.clearCount())
                    + logs.stream().mapToInt(log -> CodedOutputStream.computeByteArraySize(13, log)).sum()
                    + CodedOutputStream.computeBoolSize(14, payload.swept())
                    + CodedOutputStream.computeBoolSize(15, payload.replayed());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeString(1, payload.settlementId());
                output.writeString(2, payload.stageId());
                output.writeString(3, payload.status().name());
                output.writeInt32(4, payload.rounds());
                output.writeInt32(5, payload.playerHp());
                output.writeInt32(6, payload.enemyHp());
                output.writeByteArray(7, reward);
                output.writeString(8, payload.progressActivityId());
                output.writeInt32(9, payload.progressDelta());
                output.writeInt32(10, payload.stars());
                output.writeBool(11, payload.firstClear());
                output.writeInt32(12, payload.clearCount());
                for (byte[] log : logs) {
                    output.writeByteArray(13, log);
                }
                output.writeBool(14, payload.swept());
                output.writeBool(15, payload.replayed());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode player business result", e);
            }
        }

        @Override
        public BattleSettlementResult decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            String settlementId = "";
            String stageId = "";
            BattleSettlementStatus status = BattleSettlementStatus.VICTORY;
            int rounds = 0;
            int playerHp = 0;
            int enemyHp = 0;
            BagResult reward = new BagResult(List.of());
            String progressActivityId = "";
            int progressDelta = 0;
            int stars = 0;
            boolean firstClear = false;
            int clearCount = 0;
            List<BattleLogEntry> logs = new ArrayList<>();
            boolean swept = false;
            boolean replayed = false;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> settlementId = input.readString();
                        case 2 -> stageId = input.readString();
                        case 3 -> status = BattleSettlementStatus.valueOf(input.readString());
                        case 4 -> rounds = input.readInt32();
                        case 5 -> playerHp = input.readInt32();
                        case 6 -> enemyHp = input.readInt32();
                        case 7 -> reward = decodeBagResult(input.readByteArray());
                        case 8 -> progressActivityId = input.readString();
                        case 9 -> progressDelta = input.readInt32();
                        case 10 -> stars = input.readInt32();
                        case 11 -> firstClear = input.readBool();
                        case 12 -> clearCount = input.readInt32();
                        case 13 -> logs.add(decodeBattleLogEntry(input.readByteArray()));
                        case 14 -> swept = input.readBool();
                        case 15 -> replayed = input.readBool();
                        default -> input.skipField(tag);
                    }
                }
                return new BattleSettlementResult(settlementId, stageId, status, rounds, playerHp, enemyHp,
                        reward, progressActivityId, progressDelta, stars, firstClear, clearCount, logs,
                        swept, replayed);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player business result", e);
            }
        }
    }

    private static final class TaskClaimResultCodec implements PayloadCodec<TaskClaimResult> {
        @Override
        public String typeName() {
            return TaskClaimResult.class.getName();
        }

        @Override
        public Class<TaskClaimResult> javaType() {
            return TaskClaimResult.class;
        }

        @Override
        public byte[] encode(TaskClaimResult payload) {
            return encodeStringIntBag(payload.taskId(), payload.progress(), payload.reward());
        }

        @Override
        public TaskClaimResult decode(byte[] bytes) {
            StringIntBag decoded = decodeStringIntBag(bytes);
            return new TaskClaimResult(decoded.text(), decoded.value(), decoded.bag());
        }
    }

    private static final class AchievementClaimResultCodec implements PayloadCodec<AchievementClaimResult> {
        @Override
        public String typeName() {
            return AchievementClaimResult.class.getName();
        }

        @Override
        public Class<AchievementClaimResult> javaType() {
            return AchievementClaimResult.class;
        }

        @Override
        public byte[] encode(AchievementClaimResult payload) {
            return encodeStringIntBag(payload.achievementId(), payload.progress(), payload.reward());
        }

        @Override
        public AchievementClaimResult decode(byte[] bytes) {
            StringIntBag decoded = decodeStringIntBag(bytes);
            return new AchievementClaimResult(decoded.text(), decoded.value(), decoded.bag());
        }
    }

    private static byte[] encodeBattleStageCommand(String stageId, String settlementId) {
        int size = CodedOutputStream.computeStringSize(1, stageId)
                + CodedOutputStream.computeStringSize(2, settlementId);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, stageId);
            output.writeString(2, settlementId);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player business command", e);
        }
    }

    private static StringString decodeBattleStageCommand(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String stageId = "";
        String settlementId = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> stageId = input.readString();
                    case 2 -> settlementId = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new StringString(stageId, settlementId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player business command", e);
        }
    }

    private static byte[] encodeBagResult(BagResult result) {
        List<byte[]> changes = result.changes().stream()
                .map(PlayerBusinessCommandPayloadCodecs::encodeBagChange)
                .toList();
        int size = changes.stream()
                .mapToInt(change -> CodedOutputStream.computeByteArraySize(1, change))
                .sum();
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            for (byte[] change : changes) {
                output.writeByteArray(1, change);
            }
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode bag result", e);
        }
    }

    private static BagResult decodeBagResult(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        List<BagChange> changes = new ArrayList<>();
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                if (WireFormat.getTagFieldNumber(tag) == 1) {
                    changes.add(decodeBagChange(input.readByteArray()));
                } else {
                    input.skipField(tag);
                }
            }
            return new BagResult(changes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode bag result", e);
        }
    }

    private static byte[] encodeBagChange(BagChange change) {
        int size = CodedOutputStream.computeStringSize(1, change.itemId())
                + CodedOutputStream.computeInt32Size(2, change.before())
                + CodedOutputStream.computeInt32Size(3, change.after());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, change.itemId());
            output.writeInt32(2, change.before());
            output.writeInt32(3, change.after());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode bag change", e);
        }
    }

    private static BagChange decodeBagChange(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String itemId = "";
        int before = 0;
        int after = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> itemId = input.readString();
                    case 2 -> before = input.readInt32();
                    case 3 -> after = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new BagChange(itemId, before, after);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode bag change", e);
        }
    }

    private static byte[] encodeBattleLogEntry(BattleLogEntry log) {
        int size = CodedOutputStream.computeInt64Size(1, log.sequence())
                + CodedOutputStream.computeInt64Size(2, log.occurredAt().toEpochMilli())
                + CodedOutputStream.computeStringSize(3, log.type())
                + CodedOutputStream.computeStringSize(4, log.message());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt64(1, log.sequence());
            output.writeInt64(2, log.occurredAt().toEpochMilli());
            output.writeString(3, log.type());
            output.writeString(4, log.message());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode battle log", e);
        }
    }

    private static BattleLogEntry decodeBattleLogEntry(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long sequence = 0;
        long occurredAtMillis = Instant.EPOCH.toEpochMilli();
        String type = "";
        String message = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> sequence = input.readInt64();
                    case 2 -> occurredAtMillis = input.readInt64();
                    case 3 -> type = input.readString();
                    case 4 -> message = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new BattleLogEntry(sequence, Instant.ofEpochMilli(occurredAtMillis), type, message);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode battle log", e);
        }
    }

    private static byte[] encodeStringIntBag(String text, int value, BagResult bag) {
        byte[] bagBytes = encodeBagResult(bag);
        int size = CodedOutputStream.computeStringSize(1, text)
                + CodedOutputStream.computeInt32Size(2, value)
                + CodedOutputStream.computeByteArraySize(3, bagBytes);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, text);
            output.writeInt32(2, value);
            output.writeByteArray(3, bagBytes);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player business result", e);
        }
    }

    private static StringIntBag decodeStringIntBag(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String text = "";
        int value = 0;
        BagResult bag = new BagResult(List.of());
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> text = input.readString();
                    case 2 -> value = input.readInt32();
                    case 3 -> bag = decodeBagResult(input.readByteArray());
                    default -> input.skipField(tag);
                }
            }
            return new StringIntBag(text, value, bag);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player business result", e);
        }
    }

    private static byte[] encodeString(String text) {
        int size = CodedOutputStream.computeStringSize(1, text);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, text);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player business command", e);
        }
    }

    private static String decodeString(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String text = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                if (WireFormat.getTagFieldNumber(tag) == 1) {
                    text = input.readString();
                } else {
                    input.skipField(tag);
                }
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player business command", e);
        }
    }

    private static byte[] encodeInt(int value) {
        int size = CodedOutputStream.computeInt32Size(1, value);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt32(1, value);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player business command", e);
        }
    }

    private static int decodeInt(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        int value = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                if (WireFormat.getTagFieldNumber(tag) == 1) {
                    value = input.readInt32();
                } else {
                    input.skipField(tag);
                }
            }
            return value;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player business command", e);
        }
    }

    private static byte[] encodeStringInt(String text, int value) {
        int size = CodedOutputStream.computeStringSize(1, text)
                + CodedOutputStream.computeInt32Size(2, value);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, text);
            output.writeInt32(2, value);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode player business command", e);
        }
    }

    private static StringInt decodeStringInt(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String text = "";
        int value = 0;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> text = input.readString();
                    case 2 -> value = input.readInt32();
                    default -> input.skipField(tag);
                }
            }
            return new StringInt(text, value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode player business command", e);
        }
    }

    private record StringInt(String text, int value) {
    }

    private record StringIntBag(String text, int value, BagResult bag) {
    }

    private record StringString(String first, String second) {
    }
}
