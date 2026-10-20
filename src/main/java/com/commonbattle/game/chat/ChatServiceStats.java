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
        long failedDeliveryRecipients,
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
    public ChatServiceStats {
        persistence = java.util.Objects.requireNonNull(persistence, "persistence");
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
            long droppedHistoryMessages,
            long acceptedDeliveryRecipients,
            long droppedDeliveryRecipients,
            long failedDeliveryRecipients,
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
            long asyncAccessCallbackDeliveryFailures
    ) {
        this(
                activeChannels, activeDirectSessions, joinRequests, leaveRequests, sendRequests,
                mutedRejects, blockedRejects, retainedMessages, droppedHistoryMessages,
                acceptedDeliveryRecipients, droppedDeliveryRecipients, failedDeliveryRecipients,
                allianceEventRemovedMembers, allianceSnapshotRemovedMembers,
                asyncMessageChecks, asyncMessageSentDecisions, asyncMessageRejectedDecisions,
                asyncMessageBackpressuredDecisions, asyncMessageCallbackDeliveryFailures,
                asyncAccessChecks, asyncAccessAllowedDecisions, asyncAccessRejectedDecisions,
                asyncAccessBackpressuredDecisions, asyncAccessCallbackDeliveryFailures,
                ChatPersistenceStats.empty()
        );
    }

    public ChatServiceStats(long activeChannels, long joinRequests, long leaveRequests, long sendRequests) {
        this(activeChannels, 0, joinRequests, leaveRequests, sendRequests, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
                retainedMessages, droppedHistoryMessages, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
            long droppedHistoryMessages,
            long acceptedDeliveryRecipients,
            long droppedDeliveryRecipients,
            long failedDeliveryRecipients
    ) {
        this(activeChannels, activeDirectSessions, joinRequests, leaveRequests, sendRequests, mutedRejects, blockedRejects,
                retainedMessages, droppedHistoryMessages, acceptedDeliveryRecipients, droppedDeliveryRecipients,
                failedDeliveryRecipients, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
            long droppedHistoryMessages,
            long acceptedDeliveryRecipients,
            long droppedDeliveryRecipients,
            long failedDeliveryRecipients,
            long allianceEventRemovedMembers,
            long allianceSnapshotRemovedMembers
    ) {
        this(activeChannels, activeDirectSessions, joinRequests, leaveRequests, sendRequests, mutedRejects, blockedRejects,
                retainedMessages, droppedHistoryMessages, acceptedDeliveryRecipients, droppedDeliveryRecipients,
                failedDeliveryRecipients, allianceEventRemovedMembers, allianceSnapshotRemovedMembers,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static ChatServiceStats empty() {
        return new ChatServiceStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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
                failedDeliveryRecipients + other.failedDeliveryRecipients,
                allianceEventRemovedMembers + other.allianceEventRemovedMembers,
                allianceSnapshotRemovedMembers + other.allianceSnapshotRemovedMembers,
                asyncMessageChecks + other.asyncMessageChecks,
                asyncMessageSentDecisions + other.asyncMessageSentDecisions,
                asyncMessageRejectedDecisions + other.asyncMessageRejectedDecisions,
                asyncMessageBackpressuredDecisions + other.asyncMessageBackpressuredDecisions,
                asyncMessageCallbackDeliveryFailures + other.asyncMessageCallbackDeliveryFailures,
                asyncAccessChecks + other.asyncAccessChecks,
                asyncAccessAllowedDecisions + other.asyncAccessAllowedDecisions,
                asyncAccessRejectedDecisions + other.asyncAccessRejectedDecisions,
                asyncAccessBackpressuredDecisions + other.asyncAccessBackpressuredDecisions,
                asyncAccessCallbackDeliveryFailures + other.asyncAccessCallbackDeliveryFailures,
                persistence.plus(other.persistence)
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
                failedDeliveryRecipients,
                allianceEventRemovedMembers,
                allianceSnapshotRemovedMembers,
                asyncMessageChecks,
                asyncMessageSentDecisions,
                asyncMessageRejectedDecisions,
                asyncMessageBackpressuredDecisions,
                asyncMessageCallbackDeliveryFailures,
                asyncAccessChecks,
                asyncAccessAllowedDecisions,
                asyncAccessRejectedDecisions,
                asyncAccessBackpressuredDecisions,
                asyncAccessCallbackDeliveryFailures,
                persistence
        );
    }

    public ChatServiceStats withAsyncMessageStats(
            long checks,
            long sentDecisions,
            long rejectedDecisions,
            long backpressuredDecisions,
            long callbackDeliveryFailures
    ) {
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
                failedDeliveryRecipients,
                allianceEventRemovedMembers,
                allianceSnapshotRemovedMembers,
                checks,
                sentDecisions,
                rejectedDecisions,
                backpressuredDecisions,
                callbackDeliveryFailures,
                asyncAccessChecks,
                asyncAccessAllowedDecisions,
                asyncAccessRejectedDecisions,
                asyncAccessBackpressuredDecisions,
                asyncAccessCallbackDeliveryFailures,
                persistence
        );
    }

    public ChatServiceStats withAsyncAccessStats(
            long checks,
            long allowedDecisions,
            long rejectedDecisions,
            long backpressuredDecisions,
            long callbackDeliveryFailures
    ) {
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
                failedDeliveryRecipients,
                allianceEventRemovedMembers,
                allianceSnapshotRemovedMembers,
                asyncMessageChecks,
                asyncMessageSentDecisions,
                asyncMessageRejectedDecisions,
                asyncMessageBackpressuredDecisions,
                asyncMessageCallbackDeliveryFailures,
                checks,
                allowedDecisions,
                rejectedDecisions,
                backpressuredDecisions,
                callbackDeliveryFailures,
                persistence
        );
    }

    public ChatServiceStats withPersistenceStats(ChatPersistenceStats persistence) {
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
                failedDeliveryRecipients,
                allianceEventRemovedMembers,
                allianceSnapshotRemovedMembers,
                asyncMessageChecks,
                asyncMessageSentDecisions,
                asyncMessageRejectedDecisions,
                asyncMessageBackpressuredDecisions,
                asyncMessageCallbackDeliveryFailures,
                asyncAccessChecks,
                asyncAccessAllowedDecisions,
                asyncAccessRejectedDecisions,
                asyncAccessBackpressuredDecisions,
                asyncAccessCallbackDeliveryFailures,
                persistence
        );
    }

    public long allianceRemovedMembers() {
        return allianceEventRemovedMembers + allianceSnapshotRemovedMembers;
    }
}
