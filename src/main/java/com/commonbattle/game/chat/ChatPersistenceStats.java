package com.commonbattle.game.chat;

/**
 * Chat Agent 持久化状态与累计结果快照。
 * 当前状态用于停服排水，累计失败用于健康降级和告警。
 */
public record ChatPersistenceStats(
        long agents,
        long recoveringAgents,
        long savingAgents,
        long failedAgents,
        long pendingCommands,
        long completedLoads,
        long completedSaves,
        long loadFailures,
        long saveFailures,
        long conflicts,
        long backpressuredCommands,
        long callbackDeliveryFailures
) {
    public static ChatPersistenceStats empty() {
        return new ChatPersistenceStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public ChatPersistenceStats plus(ChatPersistenceStats other) {
        return new ChatPersistenceStats(
                agents + other.agents,
                recoveringAgents + other.recoveringAgents,
                savingAgents + other.savingAgents,
                failedAgents + other.failedAgents,
                pendingCommands + other.pendingCommands,
                completedLoads + other.completedLoads,
                completedSaves + other.completedSaves,
                loadFailures + other.loadFailures,
                saveFailures + other.saveFailures,
                conflicts + other.conflicts,
                backpressuredCommands + other.backpressuredCommands,
                callbackDeliveryFailures + other.callbackDeliveryFailures
        );
    }

    public long inFlightAgents() {
        return recoveringAgents + savingAgents;
    }
}
