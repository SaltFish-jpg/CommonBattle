package com.commonbattle.cluster.boot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 集群启动命令计划。
 * 负责把部署清单转换成稳定的 Java 进程参数列表，真正的进程编排由脚本、测试或容器平台完成。
 */
public final class ClusterLaunchPlan {
    private final List<ClusterLaunchCommand> commands;

    private ClusterLaunchPlan(List<ClusterLaunchCommand> commands) {
        this.commands = List.copyOf(commands);
    }

    public static ClusterLaunchPlan fromManifest(
            ClusterDeploymentManifest manifest,
            Path configRoot,
            String classpath
    ) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(configRoot, "configRoot");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalArgumentException("classpath required");
        }
        List<ClusterLaunchCommand> commands = new ArrayList<>();
        for (ClusterDeploymentService service : manifest.startupOrder()) {
            commands.add(command(service, configRoot, classpath));
        }
        return new ClusterLaunchPlan(commands);
    }

    public List<ClusterLaunchCommand> commands() {
        return commands;
    }

    public Optional<ClusterLaunchCommand> command(String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName");
        return commands.stream().filter(command -> command.serviceName().equals(serviceName)).findFirst();
    }

    public List<String> shellLines() {
        return commands.stream().map(ClusterLaunchCommand::shellLine).toList();
    }

    private static ClusterLaunchCommand command(
            ClusterDeploymentService service,
            Path configRoot,
            String classpath
    ) {
        Path configPath = configRoot.resolve(service.configResource()).normalize();
        return new ClusterLaunchCommand(service.name(), List.of(
                "java",
                "-cp",
                classpath,
                service.mainClassName(),
                configPath.toString()
        ));
    }
}
