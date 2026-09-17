package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.message.AgentDeliveryStatus;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.example.cross.SceneOperations;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClusterRpcDeliveryFailureMapperTest {
    private final ClusterRpcDeliveryFailureMapper mapper = new ClusterRpcDeliveryFailureMapper();

    @Test
    void mapsKnownRpcFailuresToDeliveryStatus() {
        RpcRequest<String> request = new RpcRequest<>("SCENE", SceneOperations.ENTER, "payload", String.class);

        assertEquals(AgentDeliveryStatus.TIMEOUT,
                mapper.map(new RpcTimeoutException(request, Duration.ofMillis(1))).status());
        assertEquals(AgentDeliveryStatus.CIRCUIT_OPEN,
                mapper.map(new RpcCircuitOpenException(request)).status());
        assertEquals(AgentDeliveryStatus.REJECTED,
                mapper.map(new RpcRejectedException(1)).status());
        assertEquals(AgentDeliveryStatus.REMOTE_UNAVAILABLE,
                mapper.map(new RpcNoRoutableServiceException(request)).status());
    }

    @Test
    void mapsStructuredAdmissionFailureWithRetryAfter() {
        var result = mapper.map(new RpcStructuredException(
                "mailbox_pressure:target",
                "mailbox_pressure:target",
                Duration.ofMillis(50)
        ));

        assertEquals(AgentDeliveryStatus.REJECTED, result.status());
        assertEquals("mailbox_pressure:target", result.reason());
        assertEquals(50, result.retryAfter().toMillis());
    }

    @Test
    void mapsClosedAndUnknownFailures() {
        assertEquals(AgentDeliveryStatus.SYSTEM_CLOSED,
                mapper.map(new IllegalStateException("RPC resilience gateway is closed")).status());
        assertEquals(AgentDeliveryStatus.REMOTE_UNAVAILABLE,
                mapper.map(new IllegalStateException("No service registered for SCENE")).status());
    }
}
