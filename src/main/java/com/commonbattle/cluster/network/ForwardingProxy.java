package com.commonbattle.cluster.network;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;

import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 代理服转发器。
 * 代理服不理解业务 payload，只按信封里的最终 target 转发，保持业务服务之间的网络解耦。
 */
public final class ForwardingProxy implements ClusterMessageHandler, AutoCloseable {
    private final ClusterTransport transport;
    private final ForwardingProxyConfig config;
    private final ScheduledExecutorService scheduler;
    private final Map<String, PendingForward> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicLong nextDeliveryId = new AtomicLong();
    private final AtomicLong receivedEnvelopes = new AtomicLong();
    private final AtomicLong forwardedEnvelopes = new AtomicLong();
    private final AtomicLong failedForwards = new AtomicLong();
    private final AtomicLong sentFailureReceipts = new AtomicLong();
    private final AtomicLong failedFailureReceipts = new AtomicLong();
    private final AtomicLong timedOutForwards = new AtomicLong();
    private final AtomicLong rejectedForwards = new AtomicLong();
    private final AtomicLong sentDeliveryAcks = new AtomicLong();
    private final AtomicLong failedDeliveryAcks = new AtomicLong();
    private volatile ServiceId local;

    public ForwardingProxy(ClusterTransport transport) {
        this(transport, ForwardingProxyConfig.defaults());
    }

    public ForwardingProxy(ClusterTransport transport, ForwardingProxyConfig config) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "common-battle-proxy-delivery");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ForwardingProxy(ClusterTransport transport, ServiceDescriptor proxy) {
        this(transport);
        this.local = Objects.requireNonNull(proxy, "proxy").id();
    }

    public ForwardingProxy(
            ClusterTransport transport,
            ServiceDescriptor proxy,
            ForwardingProxyConfig config
    ) {
        this(transport, config);
        this.local = Objects.requireNonNull(proxy, "proxy").id();
    }

    public void bind(ServiceDescriptor proxy) {
        Objects.requireNonNull(proxy, "proxy");
        if (local != null) {
            throw new IllegalStateException("Forwarding proxy is already bound to " + local.wireName());
        }
        local = proxy.id();
        transport.bind(proxy, this);
    }

    @Override
    public void onMessage(ClusterEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        ServiceId proxy = local;
        if (proxy == null) {
            throw new IllegalStateException("Forwarding proxy is not bound");
        }
        if (ClusterDeliveryProtocol.isAcknowledgment(envelope)) {
            acknowledge(envelope);
            return;
        }
        receivedEnvelopes.incrementAndGet();
        if (closed.get() || !reservePending()) {
            failedForwards.incrementAndGet();
            rejectedForwards.incrementAndGet();
            sendFailureReceipt(proxy, envelope, new IllegalStateException("Proxy forwarding queue is unavailable"));
            return;
        }
        String deliveryId = proxy.wireName() + ':' + nextDeliveryId.incrementAndGet();
        ClusterEnvelope forwarded = ClusterDeliveryProtocol.throughProxy(envelope, proxy, deliveryId);
        PendingForward forward = new PendingForward(envelope);
        pending.put(deliveryId, forward);
        if (closed.get()) {
            settle(deliveryId, forward);
            failedForwards.incrementAndGet();
            rejectedForwards.incrementAndGet();
            sendFailureReceipt(proxy, envelope, new IllegalStateException("Proxy forwarding queue is unavailable"));
            return;
        }
        try {
            forward.timeoutFuture = scheduler.schedule(
                    () -> deliveryTimedOut(proxy, deliveryId, forward),
                    config.deliveryAckTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException error) {
            settle(deliveryId, forward);
            failedForwards.incrementAndGet();
            rejectedForwards.incrementAndGet();
            sendFailureReceipt(proxy, envelope, error);
            return;
        }
        transport.send(envelope.target(), forwarded, new ClusterSendCallback() {
            @Override
            public void success() {
            }

            @Override
            public void failure(Throwable error) {
                if (settle(deliveryId, forward)) {
                    forward.cancelTimeout();
                    forwardingFailed(proxy, envelope, error);
                }
            }
        });
    }

    public ForwardingProxyStats stats() {
        return new ForwardingProxyStats(
                receivedEnvelopes.get(),
                forwardedEnvelopes.get(),
                failedForwards.get(),
                sentFailureReceipts.get(),
                failedFailureReceipts.get(),
                pendingCount.get(),
                timedOutForwards.get(),
                rejectedForwards.get(),
                sentDeliveryAcks.get(),
                failedDeliveryAcks.get()
        );
    }

    private void acknowledge(ClusterEnvelope acknowledgment) {
        String deliveryId = acknowledgment.metadata().get(ClusterDeliveryProtocol.DELIVERY_ID);
        if (deliveryId == null || deliveryId.isBlank()) {
            return;
        }
        PendingForward forward = pending.get(deliveryId);
        if (forward == null || !forward.envelope.target().equals(acknowledgment.source())) {
            return;
        }
        if (settle(deliveryId, forward)) {
            forward.cancelTimeout();
            forwardedEnvelopes.incrementAndGet();
            acknowledgeUpstream(Objects.requireNonNull(local, "local"), forward.envelope);
        }
    }

    private void acknowledgeUpstream(ServiceId proxy, ClusterEnvelope delivered) {
        ClusterEnvelope acknowledgment = ClusterDeliveryProtocol.acknowledgment(proxy, delivered);
        if (acknowledgment == null) {
            return;
        }
        transport.send(acknowledgment.target(), acknowledgment, new ClusterSendCallback() {
            @Override
            public void success() {
                sentDeliveryAcks.incrementAndGet();
            }

            @Override
            public void failure(Throwable error) {
                failedDeliveryAcks.incrementAndGet();
            }
        });
    }

    private void deliveryTimedOut(ServiceId proxy, String deliveryId, PendingForward forward) {
        if (!settle(deliveryId, forward)) {
            return;
        }
        timedOutForwards.incrementAndGet();
        forwardingFailed(
                proxy,
                forward.envelope,
                new IllegalStateException("Delivery ACK timed out for " + forward.envelope.target().wireName())
        );
    }

    private void forwardingFailed(ServiceId proxy, ClusterEnvelope envelope, Throwable error) {
        failedForwards.incrementAndGet();
        if (ClusterDeliveryProtocol.isFailure(envelope)) {
            failedFailureReceipts.incrementAndGet();
            return;
        }
        sendFailureReceipt(proxy, envelope, error);
    }

    private void sendFailureReceipt(ServiceId proxy, ClusterEnvelope failed, Throwable error) {
        ClusterEnvelope receipt = ClusterDeliveryProtocol.failure(proxy, failed, error);
        transport.send(failed.source(), receipt, new ClusterSendCallback() {
            @Override
            public void success() {
                sentFailureReceipts.incrementAndGet();
            }

            @Override
            public void failure(Throwable ignored) {
                failedFailureReceipts.incrementAndGet();
            }
        });
    }

    private boolean reservePending() {
        while (true) {
            int current = pendingCount.get();
            if (current >= config.maxPendingForwards()) {
                return false;
            }
            if (pendingCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private boolean settle(String deliveryId, PendingForward forward) {
        if (!pending.remove(deliveryId, forward)) {
            return false;
        }
        pendingCount.decrementAndGet();
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pending.forEach((deliveryId, forward) -> {
            if (settle(deliveryId, forward)) {
                forward.cancelTimeout();
                failedForwards.incrementAndGet();
            }
        });
        scheduler.shutdownNow();
    }

    private static final class PendingForward {
        private final ClusterEnvelope envelope;
        private volatile ScheduledFuture<?> timeoutFuture;

        private PendingForward(ClusterEnvelope envelope) {
            this.envelope = envelope;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> future = timeoutFuture;
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
