package com.commonbattle.cluster.protocol;

import java.nio.charset.StandardCharsets;

final class StringPayloadCodec implements PayloadCodec<String> {
    @Override
    public String typeName() {
        return "java.lang.String";
    }

    @Override
    public Class<String> javaType() {
        return String.class;
    }

    @Override
    public byte[] encode(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
