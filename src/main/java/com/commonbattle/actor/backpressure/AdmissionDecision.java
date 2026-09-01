package com.commonbattle.actor.backpressure;

import java.time.Duration;

/**
 * 入站消息准入结果。
 */
public record AdmissionDecision(boolean accepted, String reason, Duration retryAfter) {
    public static AdmissionDecision accept() {
        return new AdmissionDecision(true, "", Duration.ZERO);
    }

    public static AdmissionDecision reject(String reason, Duration retryAfter) {
        return new AdmissionDecision(false, reason, retryAfter);
    }
}
