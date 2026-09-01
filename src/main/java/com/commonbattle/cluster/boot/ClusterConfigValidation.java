package com.commonbattle.cluster.boot;

import java.util.List;

/**
 * 启动配置校验结果。
 */
public record ClusterConfigValidation(List<ClusterConfigIssue> issues) {
    public ClusterConfigValidation {
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
                .orElse("invalid cluster config");
        throw new IllegalArgumentException(message);
    }
}
