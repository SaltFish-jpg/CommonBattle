package com.commonbattle.cluster.network;

/**
 * 代理服逐跳转发统计。
 */
public record ForwardingProxyStats(
        long receivedEnvelopes,
        long forwardedEnvelopes,
        long failedForwards,
        long sentFailureReceipts,
        long failedFailureReceipts,
        int pendingForwards,
        long timedOutForwards,
        long rejectedForwards,
        long sentDeliveryAcks,
        long failedDeliveryAcks
) {
    public ForwardingProxyStats {
        if (receivedEnvelopes < 0 || forwardedEnvelopes < 0 || failedForwards < 0
                || sentFailureReceipts < 0 || failedFailureReceipts < 0 || pendingForwards < 0
                || timedOutForwards < 0 || rejectedForwards < 0
                || sentDeliveryAcks < 0 || failedDeliveryAcks < 0) {
            throw new IllegalArgumentException("forwarding proxy stats must not be negative");
        }
    }

    public static ForwardingProxyStats empty() {
        return new ForwardingProxyStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public ForwardingProxyStats plus(ForwardingProxyStats other) {
        return new ForwardingProxyStats(
                receivedEnvelopes + other.receivedEnvelopes,
                forwardedEnvelopes + other.forwardedEnvelopes,
                failedForwards + other.failedForwards,
                sentFailureReceipts + other.sentFailureReceipts,
                failedFailureReceipts + other.failedFailureReceipts,
                pendingForwards + other.pendingForwards,
                timedOutForwards + other.timedOutForwards,
                rejectedForwards + other.rejectedForwards,
                sentDeliveryAcks + other.sentDeliveryAcks,
                failedDeliveryAcks + other.failedDeliveryAcks
        );
    }
}
