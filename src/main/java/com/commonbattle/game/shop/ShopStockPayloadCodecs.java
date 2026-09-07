package com.commonbattle.game.shop;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;

/**
 * 商店全服库存 RPC payload codec。
 */
public final class ShopStockPayloadCodecs {
    private ShopStockPayloadCodecs() {
    }

    public static PayloadCodecRegistry registerTo(PayloadCodecRegistry registry) {
        registry.register(new ReserveRequestCodec());
        registry.register(new ReserveResponseCodec());
        registry.register(new ReleaseRequestCodec());
        registry.register(new ReleaseResponseCodec());
        registry.register(new RemainingRequestCodec());
        registry.register(new RemainingResponseCodec());
        return registry;
    }

    private static final class ReserveRequestCodec implements PayloadCodec<ShopStockReserveRequest> {
        @Override
        public String typeName() {
            return ShopStockReserveRequest.class.getName();
        }

        @Override
        public Class<ShopStockReserveRequest> javaType() {
            return ShopStockReserveRequest.class;
        }

        @Override
        public byte[] encode(ShopStockReserveRequest payload) {
            return encodeStockMutationRequest(payload.reservationId(), payload.sku(), payload.count());
        }

        @Override
        public ShopStockReserveRequest decode(byte[] bytes) {
            StockMutationRequest decoded = decodeStockMutationRequest(bytes);
            return new ShopStockReserveRequest(decoded.reservationId(), decoded.sku(), decoded.count());
        }
    }

    private static final class ReserveResponseCodec implements PayloadCodec<ShopStockReserveResponse> {
        @Override
        public String typeName() {
            return ShopStockReserveResponse.class.getName();
        }

        @Override
        public Class<ShopStockReserveResponse> javaType() {
            return ShopStockReserveResponse.class;
        }

        @Override
        public byte[] encode(ShopStockReserveResponse payload) {
            int size = CodedOutputStream.computeBoolSize(1, payload.reserved())
                    + CodedOutputStream.computeInt32Size(2, payload.remaining());
            byte[] bytes = new byte[size];
            try {
                CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                output.writeBool(1, payload.reserved());
                output.writeInt32(2, payload.remaining());
                output.flush();
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to encode shop stock response", e);
            }
        }

        @Override
        public ShopStockReserveResponse decode(byte[] bytes) {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            boolean reserved = false;
            int remaining = 0;
            try {
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (WireFormat.getTagFieldNumber(tag)) {
                        case 1 -> reserved = input.readBool();
                        case 2 -> remaining = input.readInt32();
                        default -> input.skipField(tag);
                    }
                }
                return new ShopStockReserveResponse(reserved, remaining);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode shop stock response", e);
            }
        }
    }

    private static final class ReleaseRequestCodec implements PayloadCodec<ShopStockReleaseRequest> {
        @Override
        public String typeName() {
            return ShopStockReleaseRequest.class.getName();
        }

        @Override
        public Class<ShopStockReleaseRequest> javaType() {
            return ShopStockReleaseRequest.class;
        }

        @Override
        public byte[] encode(ShopStockReleaseRequest payload) {
            return encodeStockMutationRequest(payload.reservationId(), payload.sku(), payload.count());
        }

        @Override
        public ShopStockReleaseRequest decode(byte[] bytes) {
            StockMutationRequest decoded = decodeStockMutationRequest(bytes);
            return new ShopStockReleaseRequest(decoded.reservationId(), decoded.sku(), decoded.count());
        }
    }

    private static final class ReleaseResponseCodec implements PayloadCodec<ShopStockReleaseResponse> {
        @Override
        public String typeName() {
            return ShopStockReleaseResponse.class.getName();
        }

        @Override
        public Class<ShopStockReleaseResponse> javaType() {
            return ShopStockReleaseResponse.class;
        }

        @Override
        public byte[] encode(ShopStockReleaseResponse payload) {
            return encodeInt(payload.remaining());
        }

        @Override
        public ShopStockReleaseResponse decode(byte[] bytes) {
            return new ShopStockReleaseResponse(decodeInt(bytes));
        }
    }

    private static final class RemainingRequestCodec implements PayloadCodec<ShopStockRemainingRequest> {
        @Override
        public String typeName() {
            return ShopStockRemainingRequest.class.getName();
        }

        @Override
        public Class<ShopStockRemainingRequest> javaType() {
            return ShopStockRemainingRequest.class;
        }

        @Override
        public byte[] encode(ShopStockRemainingRequest payload) {
            return encodeString(payload.sku());
        }

        @Override
        public ShopStockRemainingRequest decode(byte[] bytes) {
            return new ShopStockRemainingRequest(decodeString(bytes));
        }
    }

    private static final class RemainingResponseCodec implements PayloadCodec<ShopStockRemainingResponse> {
        @Override
        public String typeName() {
            return ShopStockRemainingResponse.class.getName();
        }

        @Override
        public Class<ShopStockRemainingResponse> javaType() {
            return ShopStockRemainingResponse.class;
        }

        @Override
        public byte[] encode(ShopStockRemainingResponse payload) {
            return encodeInt(payload.remaining());
        }

        @Override
        public ShopStockRemainingResponse decode(byte[] bytes) {
            return new ShopStockRemainingResponse(decodeInt(bytes));
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
            throw new IllegalStateException("Failed to encode shop stock payload", e);
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
            throw new IllegalStateException("Failed to decode shop stock payload", e);
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
            throw new IllegalStateException("Failed to encode shop stock payload", e);
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
            throw new IllegalStateException("Failed to decode shop stock payload", e);
        }
    }

    private static byte[] encodeStockMutationRequest(String reservationId, String sku, int count) {
        int size = CodedOutputStream.computeStringSize(1, sku)
                + CodedOutputStream.computeInt32Size(2, count)
                + CodedOutputStream.computeStringSize(3, reservationId);
        byte[] bytes = new byte[size];
        try {
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeString(1, sku);
            output.writeInt32(2, count);
            output.writeString(3, reservationId);
            output.flush();
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode shop stock payload", e);
        }
    }

    private static StockMutationRequest decodeStockMutationRequest(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        String sku = "";
        int count = 0;
        String reservationId = "";
        try {
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> sku = input.readString();
                    case 2 -> count = input.readInt32();
                    case 3 -> reservationId = input.readString();
                    default -> input.skipField(tag);
                }
            }
            return new StockMutationRequest(reservationId, sku, count);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode shop stock payload", e);
        }
    }

    private record StockMutationRequest(String reservationId, String sku, int count) {
    }
}
