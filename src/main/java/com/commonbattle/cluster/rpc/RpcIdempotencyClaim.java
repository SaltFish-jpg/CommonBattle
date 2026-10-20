package com.commonbattle.cluster.rpc;

import java.util.Objects;

/**
 * RPC 幂等执行权的领取结果。
 * ACQUIRED 表示当前实例可执行业务，IN_PROGRESS 表示其它实例持有有效租约，COMPLETED 可直接复用结果。
 */
public record RpcIdempotencyClaim(Status status, RpcIdempotencyResult result) {
    public RpcIdempotencyClaim {
        Objects.requireNonNull(status, "status");
        if (status == Status.COMPLETED) {
            Objects.requireNonNull(result, "result");
        } else if (result != null) {
            throw new IllegalArgumentException("Only completed claim may contain result");
        }
    }

    public static RpcIdempotencyClaim acquired() {
        return new RpcIdempotencyClaim(Status.ACQUIRED, null);
    }

    public static RpcIdempotencyClaim inProgress() {
        return new RpcIdempotencyClaim(Status.IN_PROGRESS, null);
    }

    public static RpcIdempotencyClaim completed(RpcIdempotencyResult result) {
        return new RpcIdempotencyClaim(Status.COMPLETED, result);
    }

    public enum Status {
        ACQUIRED,
        IN_PROGRESS,
        COMPLETED
    }
}
