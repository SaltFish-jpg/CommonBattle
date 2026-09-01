package com.commonbattle.cluster.protocol;

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨服消息 codec 注册表。
 * Netty 和测试传输共享这张表，协议是否可发送在启动阶段就能暴露。
 */
public final class PayloadCodecRegistry {
    private static final String NULL_TYPE = "common.null";
    private static final int PROTOSTUFF_BUFFER_SIZE = 512;

    private final Map<String, PayloadCodec<?>> byTypeName = new ConcurrentHashMap<>();
    private final Map<Class<?>, PayloadCodec<?>> byJavaType = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> protostuffByTypeName = new ConcurrentHashMap<>();
    private final Map<Class<?>, Schema<?>> protostuffSchemas = new ConcurrentHashMap<>();

    public static PayloadCodecRegistry commonDefaults() {
        PayloadCodecRegistry registry = new PayloadCodecRegistry();
        registry.register(new StringPayloadCodec());
        return registry;
    }

    public <T> void register(PayloadCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        byTypeName.put(codec.typeName(), codec);
        byJavaType.put(codec.javaType(), codec);
    }

    public <T> void registerProtostuffBean(Class<T> javaType) {
        Objects.requireNonNull(javaType, "javaType");
        protostuffByTypeName.put(javaType.getName(), javaType);
        protostuffSchemas.computeIfAbsent(javaType, RuntimeSchema::getSchema);
    }

    public EncodedPayload encode(Object payload) {
        if (payload == null) {
            return new EncodedPayload(PayloadEncoding.NONE, NULL_TYPE, new byte[0]);
        }
        PayloadCodec<Object> codec = findCodecByJavaType(payload.getClass());
        if (codec != null) {
            return new EncodedPayload(PayloadEncoding.PROTOBUF, codec.typeName(), codec.encode(payload));
        }
        if (protostuffByTypeName.containsKey(payload.getClass().getName())) {
            return new EncodedPayload(
                    PayloadEncoding.PROTOSTUFF,
                    payload.getClass().getName(),
                    encodeProtostuff(payload)
            );
        }
        throw new IllegalArgumentException("No payload codec for " + payload.getClass().getName());
    }

    public Object decode(String codecName, String typeName, byte[] bytes) {
        if (PayloadEncoding.NONE.equals(codecName) || NULL_TYPE.equals(typeName)) {
            return null;
        }
        if (PayloadEncoding.PROTOBUF.equals(codecName)) {
            return decodeProtobuf(typeName, bytes);
        }
        if (PayloadEncoding.PROTOSTUFF.equals(codecName)) {
            return decodeProtostuff(typeName, bytes);
        }
        throw new IllegalArgumentException("No payload encoding for " + codecName);
    }

    public Object decode(String typeName, byte[] bytes) {
        return decode(PayloadEncoding.PROTOBUF, typeName, bytes);
    }

    private Object decodeProtobuf(String typeName, byte[] bytes) {
        PayloadCodec<?> codec = byTypeName.get(typeName);
        if (codec == null) {
            throw new IllegalArgumentException("No payload codec for " + typeName);
        }
        return codec.decode(bytes);
    }

    @SuppressWarnings("unchecked")
    private PayloadCodec<Object> findCodecByJavaType(Class<?> javaType) {
        PayloadCodec<?> codec = byJavaType.get(javaType);
        return (PayloadCodec<Object>) codec;
    }

    @SuppressWarnings("unchecked")
    private <T> byte[] encodeProtostuff(T payload) {
        Schema<T> schema = (Schema<T>) protostuffSchemas.get(payload.getClass());
        LinkedBuffer buffer = LinkedBuffer.allocate(PROTOSTUFF_BUFFER_SIZE);
        try {
            return ProtostuffIOUtil.toByteArray(payload, schema, buffer);
        } finally {
            buffer.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T decodeProtostuff(String typeName, byte[] bytes) {
        Class<T> javaType = (Class<T>) protostuffByTypeName.get(typeName);
        if (javaType == null) {
            throw new IllegalArgumentException("No protostuff bean registered for " + typeName);
        }
        Schema<T> schema = (Schema<T>) protostuffSchemas.computeIfAbsent(javaType, RuntimeSchema::getSchema);
        T message = schema.newMessage();
        ProtostuffIOUtil.mergeFrom(bytes, message, schema);
        return message;
    }
}
