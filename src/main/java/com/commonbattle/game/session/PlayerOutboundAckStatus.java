package com.commonbattle.game.session;

/**
 * 玩家出站 ACK 窗口状态。
 * 网关用它识别只保活但不确认推送的慢客户端，避免未确认窗口无限占用内存。
 */
public record PlayerOutboundAckStatus(
        boolean currentSession,
        long pendingMessages,
        long oldestPendingAgeMillis,
        boolean overLimit,
        boolean timedOut
) {
    public boolean slow() {
        return currentSession && (overLimit || timedOut);
    }
}
