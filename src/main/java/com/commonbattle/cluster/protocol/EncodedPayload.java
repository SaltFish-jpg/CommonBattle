package com.commonbattle.cluster.protocol;

/**
 * 已编码的跨服 payload。
 */
public record EncodedPayload(String codecName, String typeName, byte[] bytes) {
}
