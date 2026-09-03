package com.commonbattle.cluster.boot;

import java.util.List;

/**
 * 单个服务进程的启动命令。
 * arguments 可直接交给 ProcessBuilder，shellLine 只用于日志、文档或人工复制。
 */
public record ClusterLaunchCommand(String serviceName, List<String> arguments) {
    public ClusterLaunchCommand {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName required");
        }
        arguments = List.copyOf(arguments);
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("arguments required");
        }
    }

    public String shellLine() {
        return String.join(" ", arguments.stream().map(ClusterLaunchCommand::quote).toList());
    }

    private static String quote(String value) {
        if (value.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuote = value.chars().anyMatch(Character::isWhitespace);
        if (!needsQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
