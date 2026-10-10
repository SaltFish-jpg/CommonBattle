package com.commonbattle.game.player;

import java.util.concurrent.atomic.LongAdder;

final class PlayerBusinessCommandGatewayMetrics {
    private final LongAdder remoteForwards = new LongAdder();
    private final LongAdder remoteForwardSuccessResponses = new LongAdder();
    private final LongAdder remoteForwardFailureResponses = new LongAdder();
    private final LongAdder remoteForwardTimedOutResponses = new LongAdder();
    private final LongAdder lateRemoteForwardResponses = new LongAdder();
    private final LongAdder remoteForwardMigrationProtectedResponses = new LongAdder();

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

    PlayerBusinessCommandGatewayStats snapshot() {
        return new PlayerBusinessCommandGatewayStats(
                remoteForwards.sum(),
                remoteForwardSuccessResponses.sum(),
                remoteForwardFailureResponses.sum(),
                remoteForwardTimedOutResponses.sum(),
                lateRemoteForwardResponses.sum(),
                remoteForwardMigrationProtectedResponses.sum()
        );
    }
}
