package com.commonbattle.game.player;

import java.util.concurrent.atomic.LongAdder;

final class PlayerBusinessCommandGatewayMetrics {
    private final LongAdder remoteForwards = new LongAdder();
    private final LongAdder remoteForwardSuccessResponses = new LongAdder();
    private final LongAdder remoteForwardFailureResponses = new LongAdder();
    private final LongAdder remoteForwardTimedOutResponses = new LongAdder();
    private final LongAdder lateRemoteForwardResponses = new LongAdder();
    private final LongAdder remoteForwardMigrationProtectedResponses = new LongAdder();
    private final LongAdder remoteForwardRedirectResponses = new LongAdder();
    private final LongAdder remoteForwardRedirectRetries = new LongAdder();
    private final LongAdder remoteForwardRedirectRouteRefreshes = new LongAdder();
    private final LongAdder remoteForwardRedirectRouteRefreshFailures = new LongAdder();
    private final LongAdder remoteForwardTimeoutRecoveryQueries = new LongAdder();
    private final LongAdder remoteForwardTimeoutRecoveryHits = new LongAdder();
    private final LongAdder remoteForwardTimeoutRecoveryMisses = new LongAdder();
    private final LongAdder remoteForwardTimeoutRecoveryFailures = new LongAdder();

    void remoteForward() {
        remoteForwards.increment();
    }

    void remoteForwardSuccessResponse() {
        remoteForwardSuccessResponses.increment();
    }

    void remoteForwardFailureResponse() {
        remoteForwardFailureResponses.increment();
    }

    void remoteForwardTimedOutResponse() {
        remoteForwardTimedOutResponses.increment();
    }

    void lateRemoteForwardResponse() {
        lateRemoteForwardResponses.increment();
    }

    void remoteForwardMigrationProtectedResponse() {
        remoteForwardMigrationProtectedResponses.increment();
    }

    void remoteForwardRedirectResponse() {
        remoteForwardRedirectResponses.increment();
    }

    void remoteForwardRedirectRetry() {
        remoteForwardRedirectRetries.increment();
    }

    void remoteForwardRedirectRouteRefresh() {
        remoteForwardRedirectRouteRefreshes.increment();
    }

    void remoteForwardRedirectRouteRefreshFailure() {
        remoteForwardRedirectRouteRefreshFailures.increment();
    }

    void remoteForwardTimeoutRecoveryQuery() {
        remoteForwardTimeoutRecoveryQueries.increment();
    }

    void remoteForwardTimeoutRecoveryHit() {
        remoteForwardTimeoutRecoveryHits.increment();
    }

    void remoteForwardTimeoutRecoveryMiss() {
        remoteForwardTimeoutRecoveryMisses.increment();
    }

    void remoteForwardTimeoutRecoveryFailure() {
        remoteForwardTimeoutRecoveryFailures.increment();
    }

    PlayerBusinessCommandGatewayStats snapshot() {
        return new PlayerBusinessCommandGatewayStats(
                remoteForwards.sum(),
                remoteForwardSuccessResponses.sum(),
                remoteForwardFailureResponses.sum(),
                remoteForwardTimedOutResponses.sum(),
                lateRemoteForwardResponses.sum(),
                remoteForwardMigrationProtectedResponses.sum(),
                remoteForwardRedirectResponses.sum(),
                remoteForwardRedirectRetries.sum(),
                remoteForwardRedirectRouteRefreshes.sum(),
                remoteForwardRedirectRouteRefreshFailures.sum(),
                remoteForwardTimeoutRecoveryQueries.sum(),
                remoteForwardTimeoutRecoveryHits.sum(),
                remoteForwardTimeoutRecoveryMisses.sum(),
                remoteForwardTimeoutRecoveryFailures.sum()
        );
    }
}
