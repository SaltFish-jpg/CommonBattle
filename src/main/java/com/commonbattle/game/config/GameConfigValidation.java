package com.commonbattle.game.config;

import java.util.List;

/**
 * 游戏配置校验结果。
 */
public record GameConfigValidation(List<GameConfigIssue> issues) {
    public GameConfigValidation {
        issues = List.copyOf(issues);
    }

    public boolean valid() {
        return issues.isEmpty();
    }

    public void throwIfInvalid() {
        if (valid()) {
            return;
        }
        String message = issues.stream()
                .map(issue -> issue.key() + ": " + issue.message())
                .reduce((left, right) -> left + "; " + right)
                .orElse("invalid game config");
        throw new IllegalArgumentException(message);
    }
}
