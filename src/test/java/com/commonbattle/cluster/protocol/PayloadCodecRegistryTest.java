package com.commonbattle.cluster.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadCodecRegistryTest {
    @Test
    void explicitPayloadCodecUsesProtobufEncodingName() {
        PayloadCodecRegistry registry = PayloadCodecRegistry.commonDefaults();

        EncodedPayload payload = registry.encode("ok");

        assertEquals(PayloadEncoding.PROTOBUF, payload.codecName());
        assertEquals("ok", registry.decode(payload.codecName(), payload.typeName(), payload.bytes()));
        assertTrue(registry.hasProtobufCodec(String.class));
        assertTrue(registry.hasPayloadCodec(String.class));
        assertFalse(registry.hasPayloadCodec(PrototypeRequest.class));
    }

    @Test
    void registeredBeanCanUseProtostuffDuringFastChangingBusinessStage() {
        PayloadCodecRegistry registry = PayloadCodecRegistry.commonDefaults();
        registry.registerProtostuffBean(PrototypeRequest.class);
        PrototypeRequest request = new PrototypeRequest();
        request.playerId = 10001L;
        request.feature = "auction-draft";

        EncodedPayload payload = registry.encode(request);
        PrototypeRequest decoded = (PrototypeRequest) registry.decode(payload.codecName(), payload.typeName(), payload.bytes());

        assertEquals(PayloadEncoding.PROTOSTUFF, payload.codecName());
        assertEquals(10001L, decoded.playerId);
        assertEquals("auction-draft", decoded.feature);
        assertFalse(registry.hasProtobufCodec(PrototypeRequest.class));
        assertTrue(registry.hasPayloadCodec(PrototypeRequest.class));
    }

    public static class PrototypeRequest {
        public long playerId;
        public String feature;
    }
}
