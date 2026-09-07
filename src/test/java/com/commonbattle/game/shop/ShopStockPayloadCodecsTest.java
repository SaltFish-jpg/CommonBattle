package com.commonbattle.game.shop;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopStockPayloadCodecsTest {
    @Test
    void encodesAndDecodesReserveRequestAndResponse() {
        PayloadCodecRegistry registry = ShopStockPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());

        EncodedPayload request = registry.encode(new ShopStockReserveRequest("order-10001-1", "limited_pack", 2));
        EncodedPayload response = registry.encode(new ShopStockReserveResponse(true, 8));

        assertEquals(PayloadEncoding.PROTOBUF, request.codecName());
        assertEquals(new ShopStockReserveRequest("order-10001-1", "limited_pack", 2),
                registry.decode(request.codecName(), request.typeName(), request.bytes()));
        assertEquals(new ShopStockReserveResponse(true, 8),
                registry.decode(response.codecName(), response.typeName(), response.bytes()));
    }

    @Test
    void encodesAndDecodesReleaseAndRemainingPayloads() {
        PayloadCodecRegistry registry = ShopStockPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());

        EncodedPayload release = registry.encode(new ShopStockReleaseRequest("order-10001-1", "limited_pack", 1));
        EncodedPayload releaseResponse = registry.encode(new ShopStockReleaseResponse(9));
        EncodedPayload remaining = registry.encode(new ShopStockRemainingRequest("limited_pack"));
        EncodedPayload remainingResponse = registry.encode(new ShopStockRemainingResponse(9));

        assertEquals(new ShopStockReleaseRequest("order-10001-1", "limited_pack", 1),
                registry.decode(release.codecName(), release.typeName(), release.bytes()));
        assertEquals(new ShopStockReleaseResponse(9),
                registry.decode(releaseResponse.codecName(), releaseResponse.typeName(), releaseResponse.bytes()));
        assertEquals(new ShopStockRemainingRequest("limited_pack"),
                registry.decode(remaining.codecName(), remaining.typeName(), remaining.bytes()));
        assertEquals(new ShopStockRemainingResponse(9),
                registry.decode(remainingResponse.codecName(), remainingResponse.typeName(), remainingResponse.bytes()));
    }
}
