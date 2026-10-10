package com.commonbattle.example.cross.scene;

import com.commonbattle.game.session.PlayerOutboundDeliveryResult;

/**
 * Scene shard 向玩家下行广播的端口。
 * 实现可以接入真实玩家出站中心，也可以在测试中记录广播信封。
 */
public interface SceneBroadcastSink {
    SceneBroadcastSink NOOP = envelope -> PlayerOutboundDeliveryResult.empty();

    PlayerOutboundDeliveryResult publish(SceneBroadcastEnvelope envelope);
}
