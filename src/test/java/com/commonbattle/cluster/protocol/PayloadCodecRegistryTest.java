package com.commonbattle.cluster.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PayloadCodecRegistryTest {
    @Test
    void explicitPayloadCodecUsesProtobufEncodingName() {
        PayloadCodecRegistry registry = PayloadCodecRegistry.commonDefaults();

        EncodedPayload payload = registry.encode("ok");

        assertEquals(PayloadEncoding.PROTOBUF, payload.codecName());
        assertEquals("ok", registry.decode(payload.codecName(), payload.typeName(), payload.bytes()));
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
    }

    public static class PrototypeRequest {
        public long playerId;
        public String feature;
    }
}
