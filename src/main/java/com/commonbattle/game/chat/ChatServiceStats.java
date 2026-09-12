package com.commonbattle.game.chat;

/**
 * Chat 服务运行统计。
 */
public record ChatServiceStats(
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
        long failedDeliveryRecipients
) {
    public ChatServiceStats(long activeChannels, long joinRequests, long leaveRequests, long sendRequests) {
        this(activeChannels, 0, joinRequests, leaveRequests, sendRequests, 0, 0, 0, 0, 0, 0, 0);
    }

    public ChatServiceStats(
            long activeChannels,
            long activeDirectSessions,
            long joinRequests,
            long leaveRequests,
            long sendRequests,
            long mutedRejects,
            long blockedRejects,
            long retainedMessages,
            long droppedHistoryMessages
    ) {
        this(activeChannels, activeDirectSessions, joinRequests, leaveRequests, sendRequests, mutedRejects, blockedRejects,
                retainedMessages, droppedHistoryMessages, 0, 0, 0);
    }

    public static ChatServiceStats empty() {
        return new ChatServiceStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public ChatServiceStats plus(ChatServiceStats other) {
        return new ChatServiceStats(
                activeChannels + other.activeChannels,
                activeDirectSessions + other.activeDirectSessions,
                joinRequests + other.joinRequests,
                leaveRequests + other.leaveRequests,
                sendRequests + other.sendRequests,
                mutedRejects + other.mutedRejects,
                blockedRejects + other.blockedRejects,
                retainedMessages + other.retainedMessages,
                droppedHistoryMessages + other.droppedHistoryMessages,
                acceptedDeliveryRecipients + other.acceptedDeliveryRecipients,
                droppedDeliveryRecipients + other.droppedDeliveryRecipients,
                failedDeliveryRecipients + other.failedDeliveryRecipients
        );
    }

    public ChatServiceStats withAccessRejects(long mutedRejects, long blockedRejects) {
        return new ChatServiceStats(
                activeChannels,
                activeDirectSessions,
                joinRequests,
                leaveRequests,
                sendRequests,
                mutedRejects,
                blockedRejects,
                retainedMessages,
                droppedHistoryMessages,
                acceptedDeliveryRecipients,
                droppedDeliveryRecipients,
                failedDeliveryRecipients
        );
    }
}
