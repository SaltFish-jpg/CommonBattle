package com.commonbattle.cluster.boot;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 基于 ProcessBuilder 的本地服务进程启动器。
 * 适合开发机或集成环境一键拉起默认集群；线上容器编排可复用 ClusterLaunchPlan 自行接入。
 */
public final class ProcessBuilderClusterProcessLauncher implements ClusterProcessLauncher {
    private final Duration gracefulStopTimeout;
    private final boolean inheritIo;

    public ProcessBuilderClusterProcessLauncher() {
        this(Duration.ofSeconds(3), true);
    }

    public ProcessBuilderClusterProcessLauncher(Duration gracefulStopTimeout, boolean inheritIo) {
        if (gracefulStopTimeout == null || gracefulStopTimeout.isNegative()) {
            throw new IllegalArgumentException("gracefulStopTimeout must not be negative");
        }
        this.gracefulStopTimeout = gracefulStopTimeout;
        this.inheritIo = inheritIo;
    }

    @Override
    public ClusterProcess start(ClusterLaunchCommand command) throws IOException {
        Objects.requireNonNull(command, "command");
        ProcessBuilder builder = new ProcessBuilder(command.arguments());
        builder.redirectErrorStream(true);
        if (inheritIo) {
            builder.inheritIO();
        }
        return new JavaClusterProcess(command.serviceName(), builder.start(), gracefulStopTimeout);
    }

    private static final class JavaClusterProcess implements ClusterProcess {
        private final String serviceName;
        private final Process process;
        private final Duration gracefulStopTimeout;

        private JavaClusterProcess(String serviceName, Process process, Duration gracefulStopTimeout) {
            this.serviceName = serviceName;
            this.process = process;
            this.gracefulStopTimeout = gracefulStopTimeout;
        }

        @Override
        public String serviceName() {
            return serviceName;
        }

        @Override
        public boolean alive() {
            return process.isAlive();
        }

        @Override
        public void close() {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(gracefulStopTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(gracefulStopTimeout.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IllegalStateException("Interrupted while stopping process " + serviceName, e);
            }
        }
    }
}
