package com.commonbattle.cluster.network;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForwardingProxyTest {
    @Test
    void forwardsEnvelopeAndRecordsReturnProxy() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ServiceDescriptor proxy = descriptor(ServiceKind.PROXY, "proxy-1", 9001);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9002);
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9003);
        AtomicReference<ClusterEnvelope> received = new AtomicReference<>();
        ForwardingProxy forwarder = new ForwardingProxy(transport);
        forwarder.bind(proxy);
        transport.bind(scene, envelope -> {
            received.set(envelope);
            transport.send(proxy.id(), ClusterDeliveryProtocol.acknowledgment(scene.id(), envelope));
        });
        ClusterEnvelope request = new ClusterEnvelope(1, game.id(), scene.id(), "scene.echo", "hello");

        transport.send(proxy.id(), request);

        assertEquals(proxy.id().wireName(),
                received.get().metadata().get(ClusterDeliveryProtocol.RETURN_PROXY));
        assertEquals(1, forwarder.stats().receivedEnvelopes());
        assertEquals(1, forwarder.stats().forwardedEnvelopes());
        assertEquals(0, forwarder.stats().failedForwards());
        assertEquals(0, forwarder.stats().pendingForwards());
        forwarder.close();
    }

    @Test
    void reportsDownstreamFailureToOriginalSource() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ServiceDescriptor proxy = descriptor(ServiceKind.PROXY, "proxy-1", 9001);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9002);
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9003);
        AtomicReference<ClusterEnvelope> receipt = new AtomicReference<>();
        transport.bind(game, receipt::set);
        ForwardingProxy forwarder = new ForwardingProxy(transport);
        forwarder.bind(proxy);

        transport.send(proxy.id(), new ClusterEnvelope(
                7, game.id(), scene.id(), "scene.echo", "hello"
        ));

        assertEquals(ClusterDeliveryProtocol.FAILURE_OPERATION, receipt.get().operation());
        assertEquals(game.id(), receipt.get().target());
        assertEquals(scene.id().wireName(),
                receipt.get().metadata().get(ClusterDeliveryProtocol.FAILED_TARGET));
        assertEquals("scene.echo",
                receipt.get().metadata().get(ClusterDeliveryProtocol.FAILED_OPERATION));
        assertEquals(1, forwarder.stats().failedForwards());
        assertEquals(1, forwarder.stats().sentFailureReceipts());
        assertEquals(0, forwarder.stats().failedFailureReceipts());
        forwarder.close();
    }

    @Test
    void missingTargetAcknowledgmentProducesTimedOutFailureReceipt() throws InterruptedException {
        LocalClusterTransport transport = new LocalClusterTransport();
        ServiceDescriptor proxy = descriptor(ServiceKind.PROXY, "proxy-1", 9001);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9002);
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9003);
        CountDownLatch receipt = new CountDownLatch(1);
        transport.bind(game, ignored -> receipt.countDown());
        transport.bind(scene, ignored -> {
        });
        ForwardingProxy forwarder = new ForwardingProxy(
                transport, new ForwardingProxyConfig(Duration.ofMillis(20), 8)
        );
        forwarder.bind(proxy);

        transport.send(proxy.id(), new ClusterEnvelope(
                8, game.id(), scene.id(), "scene.echo", "hello"
        ));

        assertEquals(1, forwarder.stats().pendingForwards());
        org.junit.jupiter.api.Assertions.assertTrue(receipt.await(1, TimeUnit.SECONDS));
        assertEquals(0, forwarder.stats().pendingForwards());
        assertEquals(1, forwarder.stats().timedOutForwards());
        assertEquals(1, forwarder.stats().failedForwards());
        forwarder.close();
    }

    @Test
    void replacesImmediateReturnProxyForEveryForwardingHop() {
        ServiceDescriptor firstProxy = descriptor(ServiceKind.PROXY, "proxy-1", 9001);
        ServiceDescriptor secondProxy = descriptor(ServiceKind.PROXY, "proxy-2", 9002);
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9003);
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9004);
        ClusterEnvelope original = ClusterDeliveryProtocol.throughProxy(
                new ClusterEnvelope(1, game.id(), scene.id(), "scene.echo", "hello"),
                firstProxy.id()
        );

        ClusterEnvelope forwarded = ClusterDeliveryProtocol.throughProxy(
                original, secondProxy.id(), "delivery-2"
        );

        assertEquals(secondProxy.id(), ClusterDeliveryProtocol.returnProxy(forwarded));
        assertEquals("delivery-2", forwarded.metadata().get(ClusterDeliveryProtocol.DELIVERY_ID));
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(),
                Map.of()
        );
    }
}
