package com.commonbattle.cluster.boot;

import java.util.List;

/**
 * 部署清单校验结果。
 * 启动脚本和测试可以先收集全部问题，再决定是否终止发布。
 */
public record ClusterDeploymentValidation(List<ClusterDeploymentIssue> issues) {
    public ClusterDeploymentValidation {
        issues = List.copyOf(issues);
    }

    public boolean valid() {
        return issues.isEmpty();
    }

    public void throwIfInvalid() {
        if (valid()) {
            return;
        }
        StringBuilder message = new StringBuilder("Invalid cluster deployment manifest:");
        for (ClusterDeploymentIssue issue : issues) {
            message.append(System.lineSeparator())
                    .append("- [")
                    .append(issue.serviceName())
                    .append("] ")
                    .append(issue.key())
                    .append(": ")
                    .append(issue.message());
        }
        throw new IllegalArgumentException(message.toString());
    }
}
