package com.commonbattle.cluster.boot;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.PayloadEncoding;
import com.commonbattle.game.player.PlayerPushPayloads;
import com.commonbattle.game.session.PlayerClientEnvelope;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;
import com.commonbattle.game.session.ProtoPlayerClientCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BootPayloadCodecsTest {
    @Test
    void gameServerCodecsIncludePlayerClientPushPayloads() {
        PayloadCodecRegistry registry = BootPayloadCodecs.gameServer();
        ProtoPlayerClientCodec codec = new ProtoPlayerClientCodec(registry);

        PlayerClientEnvelope decoded = codec.decode(codec.encode(new PlayerClientEnvelope(
                10001L,
                PlayerOutboundTopicPolicies.BAG_SNAPSHOT,
                PlayerPushPayloads.bag(new com.commonbattle.game.bag.BagSnapshot(Map.of("gold", 100))),
                1,
                Instant.parse("2026-09-01T00:00:00Z")
        )));

        PlayerPushPayloads.BagSnapshotPayload payload =
                assertInstanceOf(PlayerPushPayloads.BagSnapshotPayload.class, decoded.payload());
        assertEquals(100, payload.itemCounts.get("gold"));
        assertEquals(PayloadEncoding.PROTOSTUFF, registry.encode(payload).codecName());
    }
}
