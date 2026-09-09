package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.RegistryEvent;
import com.commonbattle.cluster.RegistryEventType;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegistryPayloadCodecsTest {
    @Test
    void registerRequestCodecKeepsLeaseTtl() {
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        RegistryRegisterRequest request = new RegistryRegisterRequest(
                descriptor(ServiceKind.GAME, "game-1"),
                Duration.ofSeconds(15)
        );

        EncodedPayload encoded = codecs.encode(request);
        RegistryRegisterRequest decoded = (RegistryRegisterRequest) codecs.decode(
                encoded.codecName(),
                encoded.typeName(),
                encoded.bytes()
        );

        assertEquals(request, decoded);
    }

    @Test
    void listResponseCodecKeepsSnapshotVersion() {
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        RegistryListResponse response = new RegistryListResponse(
                List.of(descriptor(ServiceKind.SCENE, "scene-1")),
                12
        );

        EncodedPayload encoded = codecs.encode(response);
        RegistryListResponse decoded = (RegistryListResponse) codecs.decode(
                encoded.codecName(),
                encoded.typeName(),
                encoded.bytes()
        );

        assertEquals(response, decoded);
    }

    @Test
    void subscribeRequestCodecKeepsSinceVersion() {
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        RegistrySubscribeRequest request = new RegistrySubscribeRequest(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceKind.SCENE,
                7,
                Duration.ofSeconds(12)
        );

        EncodedPayload encoded = codecs.encode(request);
        RegistrySubscribeRequest decoded = (RegistrySubscribeRequest) codecs.decode(
                encoded.codecName(),
                encoded.typeName(),
                encoded.bytes()
        );

        assertEquals(request, decoded);
    }

    @Test
    void unsubscribeRequestCodecKeepsSubscriberAndKind() {
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        RegistryUnsubscribeRequest request = new RegistryUnsubscribeRequest(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceKind.SCENE
        );

        EncodedPayload encoded = codecs.encode(request);
        RegistryUnsubscribeRequest decoded = (RegistryUnsubscribeRequest) codecs.decode(
                encoded.codecName(),
                encoded.typeName(),
                encoded.bytes()
        );

        assertEquals(request, decoded);
    }

    @Test
    void replayPayloadCodecsKeepVersionedEvents() {
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        RegistryReplayResponse response = new RegistryReplayResponse(
                List.of(new RegistryEvent(RegistryEventType.REGISTERED, descriptor(ServiceKind.SCENE, "scene-1"), 8)),
                9,
                false,
                3
        );

        EncodedPayload encoded = codecs.encode(response);
        RegistryReplayResponse decoded = (RegistryReplayResponse) codecs.decode(
                encoded.codecName(),
                encoded.typeName(),
                encoded.bytes()
        );

        assertEquals(response, decoded);
    }

    @Test
    void heartbeatRequestCodecKeepsLeaseTtl() {
        PayloadCodecRegistry codecs = RegistryPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        RegistryHeartbeatRequest request = new RegistryHeartbeatRequest(
                ServiceId.of(ServiceKind.SCENE, "r1", "scene-1"),
                Duration.ofSeconds(5)
        );

        EncodedPayload encoded = codecs.encode(request);
        RegistryHeartbeatRequest decoded = (RegistryHeartbeatRequest) codecs.decode(
                encoded.codecName(),
                encoded.typeName(),
                encoded.bytes()
        );

        assertEquals(request, decoded);
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", 9000),
                Set.of(kind.name().toLowerCase() + ".heartbeat"),
                Map.of("mode", "lease")
        );
    }
}
