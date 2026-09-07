package com.commonbattle.game.shop;

import com.commonbattle.game.bag.BagChange;
import com.commonbattle.game.bag.BagResult;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 商店购买订单 protobuf 序列化器。
 * 字段号只追加不复用，保证灰度发布期间新旧版本能跳过未知字段。
 */
public final class ProtoShopPurchaseOrderSerializer implements ShopPurchaseOrderSerializer {
    @Override
    public byte[] serialize(ShopPurchaseOrder order) {
        Objects.requireNonNull(order, "order");
        byte[] result = encodeResult(order.result());
        int size = CodedOutputStream.computeStringSize(1, order.orderId())
                + CodedOutputStream.computeStringSize(2, order.sku())
                + CodedOutputStream.computeInt32Size(3, order.quantity())
                + CodedOutputStream.computeByteArraySize(4, result)
                + CodedOutputStream.computeInt64Size(5, order.createdAt().toEpochMilli());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, order.orderId());
            output.writeString(2, order.sku());
            output.writeInt32(3, order.quantity());
            output.writeByteArray(4, result);
            output.writeInt64(5, order.createdAt().toEpochMilli());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize shop purchase order", e);
        }
    }

    @Override
    public ShopPurchaseOrder deserialize(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String orderId = "";
        String sku = "";
        int quantity = 0;
        ShopPurchaseResult result = null;
        Instant createdAt = Instant.EPOCH;
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> orderId = input.readString();
                    case 2 -> sku = input.readString();
                    case 3 -> quantity = input.readInt32();
                    case 4 -> result = decodeResult(input.readByteArray());
                    case 5 -> createdAt = Instant.ofEpochMilli(input.readInt64());
                    default -> input.skipField(tag);
                }
            }
            return new ShopPurchaseOrder(orderId, sku, quantity, result, createdAt);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize shop purchase order", e);
        }
    }

    private static byte[] encodeResult(ShopPurchaseResult result) {
        byte[] cost = encodeBagResult(result.cost());
        byte[] reward = encodeBagResult(result.reward());
        int size = CodedOutputStream.computeStringSize(1, result.status().name())
                + CodedOutputStream.computeStringSize(2, result.sku())
                + CodedOutputStream.computeInt32Size(3, result.quantity())
                + CodedOutputStream.computeByteArraySize(4, cost)
                + CodedOutputStream.computeByteArraySize(5, reward)
                + CodedOutputStream.computeInt32Size(6, result.lifetimePurchased())
                + CodedOutputStream.computeInt32Size(7, result.dailyPurchased())
                + CodedOutputStream.computeBoolSize(8, result.replayed());
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, result.status().name());
            output.writeString(2, result.sku());
            output.writeInt32(3, result.quantity());
            output.writeByteArray(4, cost);
            output.writeByteArray(5, reward);
            output.writeInt32(6, result.lifetimePurchased());
            output.writeInt32(7, result.dailyPurchased());
            output.writeBool(8, result.replayed());
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode shop purchase result", e);
        }
    }

    private static ShopPurchaseResult decodeResult(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        ShopPurchaseStatus status = ShopPurchaseStatus.SUCCESS;
        String sku = "";
        int quantity = 0;
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
            return new ShopPurchaseResult(status, sku, quantity, cost, reward, lifetimePurchased, dailyPurchased,
                    replayed);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode shop purchase result", e);
        }
    }

    private static byte[] encodeBagResult(BagResult result) {
        int size = result.changes().stream()
                .map(ProtoShopPurchaseOrderSerializer::encodeBagChange)
                .mapToInt(change -> CodedOutputStream.computeByteArraySize(1, change))
                .sum();
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            for (BagChange change : result.changes()) {
                output.writeByteArray(1, encodeBagChange(change));
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
}
