package com.commonbattle.example.cross.scene;

import com.commonbattle.game.session.PlayerOutboundDeliveryResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 Scene 广播记录器。
 */
public final class InMemorySceneBroadcastSink implements SceneBroadcastSink {
    private final List<SceneBroadcastEnvelope> envelopes = new ArrayList<>();

    @Override
    public synchronized PlayerOutboundDeliveryResult publish(SceneBroadcastEnvelope envelope) {
        envelopes.add(envelope);
        return new PlayerOutboundDeliveryResult(envelope.recipients().size(), 0, 0, 0);
    }

    public synchronized List<SceneBroadcastEnvelope> envelopes() {
        return List.copyOf(envelopes);
    }
}
