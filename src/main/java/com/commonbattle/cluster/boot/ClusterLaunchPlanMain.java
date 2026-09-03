package com.commonbattle.cluster.boot;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * 打印默认集群的启动命令。
 * 不直接拉起进程，只把部署清单转换为可审查、可复制的命令列表。
 */
public final class ClusterLaunchPlanMain {
    private ClusterLaunchPlanMain() {
    }

    public static void main(String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: ClusterLaunchPlanMain [configRoot] [classpath]");
        }
        Path configRoot = args.length >= 1 ? Path.of(args[0]) : Path.of("src/main/resources");
        String classpath = args.length >= 2 ? args[1] : defaultClasspath();
        ClusterDeploymentManifest manifest = ClusterDeploymentManifest.defaultManifest();
        manifest.validate().throwIfInvalid();
        render(ClusterLaunchPlan.fromManifest(manifest, configRoot, classpath)).forEach(System.out::println);
    }

    static List<String> render(ClusterLaunchPlan plan) {
        return plan.commands().stream()
                .map(command -> command.serviceName() + "=" + command.shellLine())
                .toList();
    }

    private static String defaultClasspath() {
        return String.join(File.pathSeparator, List.of("target/classes", "target/dependency/*"));
    }
}
