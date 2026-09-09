package com.commonbattle.game.chat;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatPayloadCodecsTest {
    @Test
    void roundTripsChatRpcPayloadsWithProtobufCodecs() {
        PayloadCodecRegistry registry = ChatPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());

        assertEquals(new ChatJoinRequest("world", 10001L, 900L),
                roundTrip(registry, new ChatJoinRequest("world", 10001L, 900L)));
        assertEquals(new ChatLeaveRequest("world", 10001L),
                roundTrip(registry, new ChatLeaveRequest("world", 10001L)));
        assertEquals(new ChatSendRequest("world", 10001L, "hello", 5),
                roundTrip(registry, new ChatSendRequest("world", 10001L, "hello", 5)));
        assertEquals(new ChatJoinResult(ChatJoinStatus.JOINED, "world", 10001L, 3),
                roundTrip(registry, new ChatJoinResult(ChatJoinStatus.JOINED, "world", 10001L, 3)));
        assertEquals(new ChatLeaveResult(ChatLeaveStatus.LEFT, "world", 10001L, 2),
                roundTrip(registry, new ChatLeaveResult(ChatLeaveStatus.LEFT, "world", 10001L, 2)));
        ChatDelivery delivery = new ChatDelivery(
                "world",
                10001L,
                "Hero",
                "hello",
                9,
                Instant.parse("2026-09-01T00:00:00Z")
        );
        assertEquals(ChatSendResult.sent(delivery), roundTrip(registry, ChatSendResult.sent(delivery)));
        assertEquals(ChatSendResult.rejected(ChatSendStatus.STALE_PROFILE),
                roundTrip(registry, ChatSendResult.rejected(ChatSendStatus.STALE_PROFILE)));
    }

    private static Object roundTrip(PayloadCodecRegistry registry, Object payload) {
        var encoded = registry.encode(payload);
        return registry.decode(encoded.codecName(), encoded.typeName(), encoded.bytes());
    }
}
