package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.ProtoClusterCodec;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ClusterEventPayloadCodecsTest {
    @Test
    void eventPublishRequestCanPassThroughClusterEnvelope() {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        ProtoClusterCodec codec = new ProtoClusterCodec(registry);
        ProfileChangedEvent event = new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        20,
                        new AppearanceSummary("avatar_2", "frame_1", "costume_9"),
                        new AllianceBrief(100, "alliance", "badge"),
                        new FriendBrief(9, 3),
                        7,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
        ClusterEnvelope envelope = new ClusterEnvelope(
                1,
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.CENTER, "r1", "center-1"),
                ClusterEventOperations.PUBLISH,
                new EventPublishRequest(event)
        );

        ClusterEnvelope decoded = codec.decode(codec.encode(envelope));

        EventPublishRequest request = assertInstanceOf(EventPublishRequest.class, decoded.payload());
        ProfileChangedEvent decodedEvent = assertInstanceOf(ProfileChangedEvent.class, request.event());
        assertEquals(7, decodedEvent.revision());
        assertEquals("avatar_2", decodedEvent.snapshot().appearance().avatar());
    }
}
