package com.commonbattle.game.player;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import com.commonbattle.game.session.PlayerCommand;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

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
        registry.register(new ActivityProgressCommandCodec());
        registry.register(new ClaimActivityCommandCodec());
        registry.register(new UseExpItemsCommandCodec());
        registry.register(new BuyShopItemCommandCodec());
        registry.register(new BuyShopItemAsyncCommandCodec());
        registry.register(new BattleStageClearCommandCodec());
        registry.register(new BattleStageSweepCommandCodec());
        registry.register(new ClaimTaskCommandCodec());
        registry.register(new ClaimAchievementCommandCodec());
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
                    + CodedOutputStream.computeByteArraySize(11, nested.bytes());
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
                        responsePayload
                );
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode player business response", e);
            }
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

    private record StringString(String first, String second) {
    }
}
