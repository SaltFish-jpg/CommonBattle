package com.commonbattle.cluster.protocol;

/**
 * 跨服 payload 的显式编解码器。
 * 新增业务消息时注册 codec，避免传输层退化为不透明的 Java 对象序列化。
 */
public interface PayloadCodec<T> {
    String typeName();

    Class<T> javaType();

    byte[] encode(T payload);

    T decode(byte[] bytes);
}
