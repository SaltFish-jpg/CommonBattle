package com.commonbattle.cluster.rpc;

/**
 * 跨服 RPC 网关运行统计。
 */
public record RpcGatewayStats(
        long sentRequests,
        long succeededRequests,
        long failedRequests,
        long timedOutRequests,
        long rejectedRequests,
        long slowRequests,
        int pendingRequests,
        int idempotencyCacheSize,
        long forwardingFailedRequests,
        long requestFailovers,
        long replyWriteFailures,
        long replyFailovers,
        long undeliverableReplies,
        int pendingDeliveryAcks,
        long deliveryAckTimeouts,
        long deliveryAckWriteFailures,
        int idempotencyInFlightRequests,
        long idempotencyJoinedRequests
) {
    public static RpcGatewayStats empty() {
        return new RpcGatewayStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public RpcGatewayStats plus(RpcGatewayStats other) {
        return new RpcGatewayStats(
                sentRequests + other.sentRequests,
                succeededRequests + other.succeededRequests,
                failedRequests + other.failedRequests,
                timedOutRequests + other.timedOutRequests,
                rejectedRequests + other.rejectedRequests,
                slowRequests + other.slowRequests,
                pendingRequests + other.pendingRequests,
                idempotencyCacheSize + other.idempotencyCacheSize,
                forwardingFailedRequests + other.forwardingFailedRequests,
                requestFailovers + other.requestFailovers,
                replyWriteFailures + other.replyWriteFailures,
                replyFailovers + other.replyFailovers,
                undeliverableReplies + other.undeliverableReplies,
                pendingDeliveryAcks + other.pendingDeliveryAcks,
                deliveryAckTimeouts + other.deliveryAckTimeouts,
                deliveryAckWriteFailures + other.deliveryAckWriteFailures,
                idempotencyInFlightRequests + other.idempotencyInFlightRequests,
                idempotencyJoinedRequests + other.idempotencyJoinedRequests
        );
    }
}
