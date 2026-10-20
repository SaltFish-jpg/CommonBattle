package com.commonbattle.observability;

import com.commonbattle.game.chat.ChatServiceStats;
import com.commonbattle.game.chat.ChatPersistenceStats;

/**
 * Chat 运行时聚合健康统计。
 */
public record ChatRuntimeHealthStats(
        int runtimeCount,
        long activeChannels,
        long activeDirectSessions,
        long joinRequests,
        long leaveRequests,
        long sendRequests,
        long mutedRejects,
        long blockedRejects,
        long retainedMessages,
        long droppedHistoryMessages,
        long acceptedDeliveryRecipients,
        long droppedDeliveryRecipients,
        long failedDeliveryRecipients,
        long allianceRemovedMembers,
        long allianceEventRemovedMembers,
        long allianceSnapshotRemovedMembers,
        long asyncMessageChecks,
        long asyncMessageSentDecisions,
        long asyncMessageRejectedDecisions,
        long asyncMessageBackpressuredDecisions,
        long asyncMessageCallbackDeliveryFailures,
        long asyncAccessChecks,
        long asyncAccessAllowedDecisions,
        long asyncAccessRejectedDecisions,
        long asyncAccessBackpressuredDecisions,
        long asyncAccessCallbackDeliveryFailures,
        ChatPersistenceStats persistence
) {
    public static ChatRuntimeHealthStats empty() {
        return new ChatRuntimeHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ChatPersistenceStats.empty());
    }

    public static ChatRuntimeHealthStats from(int runtimeCount, ChatServiceStats stats) {
        return new ChatRuntimeHealthStats(
                runtimeCount,
                stats.activeChannels(),
                stats.activeDirectSessions(),
                stats.joinRequests(),
                stats.leaveRequests(),
                stats.sendRequests(),
                stats.mutedRejects(),
                stats.blockedRejects(),
                stats.retainedMessages(),
                stats.droppedHistoryMessages(),
                stats.acceptedDeliveryRecipients(),
                stats.droppedDeliveryRecipients(),
                stats.failedDeliveryRecipients(),
                stats.allianceRemovedMembers(),
                stats.allianceEventRemovedMembers(),
                stats.allianceSnapshotRemovedMembers(),
                stats.asyncMessageChecks(),
                stats.asyncMessageSentDecisions(),
                stats.asyncMessageRejectedDecisions(),
                stats.asyncMessageBackpressuredDecisions(),
                stats.asyncMessageCallbackDeliveryFailures(),
                stats.asyncAccessChecks(),
                stats.asyncAccessAllowedDecisions(),
                stats.asyncAccessRejectedDecisions(),
                stats.asyncAccessBackpressuredDecisions(),
                stats.asyncAccessCallbackDeliveryFailures(),
                stats.persistence()
        );
    }
}
