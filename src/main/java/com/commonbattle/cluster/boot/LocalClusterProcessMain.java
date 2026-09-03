package com.commonbattle.cluster.boot;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 本地开发集群启动入口。
 * 该入口会按部署清单启动多个 Java 服务进程，并在当前进程退出时统一停止它们。
 */
public final class LocalClusterProcessMain {
    private LocalClusterProcessMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: LocalClusterProcessMain [configRoot] [classpath]");
        }
        ClusterDeploymentManifest manifest = ClusterDeploymentManifest.defaultManifest();
        manifest.validate().throwIfInvalid();
        Path configRoot = args.length >= 1 ? Path.of(args[0]) : Path.of("src/main/resources");
        String classpath = args.length >= 2 ? args[1] : defaultClasspath();
        ClusterLaunchPlan plan = ClusterLaunchPlan.fromManifest(manifest, configRoot, classpath);
        LocalClusterProcessGroup group = new LocalClusterProcessManager(
                new ProcessBuilderClusterProcessLauncher(),
                OpsHttpReadinessProbe.live(manifest),
                ClusterStartupPolicy.defaults()
        ).start(plan);
        Runtime.getRuntime().addShutdownHook(new Thread(group::close, "common-battle-local-cluster-shutdown"));
        System.out.println("Local cluster started, processes=" + group.processes().size()
                + ", alive=" + group.aliveCount());
        new CountDownLatch(1).await();
    }

    private static String defaultClasspath() {
        return String.join(File.pathSeparator, List.of("target/classes", "target/dependency/*"));
    }
}
