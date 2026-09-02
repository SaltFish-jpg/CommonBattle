package com.commonbattle.example.cross;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;

/**
 * 跨服示例消息的 codec 注册入口。
 */
public final class CrossPayloadCodecs {
    private CrossPayloadCodecs() {
    }

    public static PayloadCodecRegistry create() {
        PayloadCodecRegistry registry = PayloadCodecRegistry.commonDefaults();
        registry.register(new EnterSceneRequestCodec());
        registry.register(new EnterSceneResultCodec());
        registry.register(new LeaveSceneRequestCodec());
        registry.register(new LeaveSceneResultCodec());
        registry.register(new RpcErrorPayloadCodec());
        return registry;
    }
}
