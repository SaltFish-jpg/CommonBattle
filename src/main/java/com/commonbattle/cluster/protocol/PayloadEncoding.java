package com.commonbattle.cluster.protocol;

/**
 * 跨服 payload 使用的编码类型。
 */
public final class PayloadEncoding {
    public static final String NONE = "none";
    public static final String PROTOBUF = "protobuf";
    public static final String PROTOSTUFF = "protostuff";

    private PayloadEncoding() {
    }
}
