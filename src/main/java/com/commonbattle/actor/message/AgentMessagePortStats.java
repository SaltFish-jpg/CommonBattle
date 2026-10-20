package com.commonbattle.actor.message;

/**
 * Agent 通信端口统计。
 * 用于观察本地邮箱投递、ask 链路和远程回包回投的可靠性。
 */
public record AgentMessagePortStats(
        long localTellAttempts,
        long localTellAccepted,
        long localTellRejected,
        long localAskStarted,
        long localAskSucceeded,
        long localAskFailed,
        long localAskTimedOut,
        long localAskTargetDeliveryFailures,
        long localAskCallbackDeliveryFailures,
        long remoteCalls,
        long remoteSucceeded,
        long remoteFailed,
        long remoteTimedOut,
        long remoteLateResponses,
        long remoteCallbackDeliveryFailures,
        long currentInFlightRemoteCalls
) {
    public static AgentMessagePortStats empty() {
        return new AgentMessagePortStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public AgentMessagePortStats plus(AgentMessagePortStats other) {
        return new AgentMessagePortStats(
                localTellAttempts + other.localTellAttempts,
                localTellAccepted + other.localTellAccepted,
                localTellRejected + other.localTellRejected,
                localAskStarted + other.localAskStarted,
                localAskSucceeded + other.localAskSucceeded,
                localAskFailed + other.localAskFailed,
                localAskTimedOut + other.localAskTimedOut,
                localAskTargetDeliveryFailures + other.localAskTargetDeliveryFailures,
                localAskCallbackDeliveryFailures + other.localAskCallbackDeliveryFailures,
                remoteCalls + other.remoteCalls,
                remoteSucceeded + other.remoteSucceeded,
                remoteFailed + other.remoteFailed,
                remoteTimedOut + other.remoteTimedOut,
                remoteLateResponses + other.remoteLateResponses,
                remoteCallbackDeliveryFailures + other.remoteCallbackDeliveryFailures,
                currentInFlightRemoteCalls + other.currentInFlightRemoteCalls
        );
    }
}
