package com.commonbattle.observability;

import java.time.Duration;
import java.util.Objects;

/**
 * 停服排水结果。
 */
public record DrainResult(boolean drained, Duration elapsed, RuntimeHealthSnapshot lastSnapshot, String reason) {
    public DrainResult(boolean drained, Duration elapsed, RuntimeHealthSnapshot lastSnapshot) {
        this(drained, elapsed, lastSnapshot, "");
    }

    public DrainResult {
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(lastSnapshot, "lastSnapshot");
        reason = Objects.requireNonNullElse(reason, "");
    }
}
