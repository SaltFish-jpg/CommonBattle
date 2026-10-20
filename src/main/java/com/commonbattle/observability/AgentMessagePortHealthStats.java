package com.commonbattle.observability;

import com.commonbattle.actor.message.AgentMessagePortStats;

/**
 * 通用 Agent 通信端口聚合健康统计。
 */
public record AgentMessagePortHealthStats(
        int portCount,
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
    public static AgentMessagePortHealthStats empty() {
        return new AgentMessagePortHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static AgentMessagePortHealthStats from(AgentMessagePortStats stats) {
        return new AgentMessagePortHealthStats(
                1,
                stats.localTellAttempts(),
                stats.localTellAccepted(),
                stats.localTellRejected(),
                stats.localAskStarted(),
                stats.localAskSucceeded(),
                stats.localAskFailed(),
                stats.localAskTimedOut(),
                stats.localAskTargetDeliveryFailures(),
                stats.localAskCallbackDeliveryFailures(),
                stats.remoteCalls(),
                stats.remoteSucceeded(),
                stats.remoteFailed(),
                stats.remoteTimedOut(),
                stats.remoteLateResponses(),
                stats.remoteCallbackDeliveryFailures(),
                stats.currentInFlightRemoteCalls()
        );
    }

    public AgentMessagePortHealthStats plus(AgentMessagePortHealthStats other) {
        return new AgentMessagePortHealthStats(
                portCount + other.portCount,
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
