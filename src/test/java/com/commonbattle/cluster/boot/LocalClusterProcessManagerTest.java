package com.commonbattle.cluster.boot;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalClusterProcessManagerTest {
    @Test
    void startsProcessesInLaunchPlanOrder() {
        RecordingLauncher launcher = new RecordingLauncher();
        ClusterLaunchPlan plan = ClusterLaunchPlan.fromManifest(
                ClusterDeploymentManifest.defaultManifest(),
                Path.of("src/main/resources"),
                "target/classes"
        );

        LocalClusterProcessGroup group = new LocalClusterProcessManager(launcher).start(plan);

        assertEquals(List.of("center", "proxy", "game", "scene-small", "scene-large", "region"), launcher.started);
        assertEquals(6, group.processes().size());
        assertEquals(6, group.aliveCount());
    }

    @Test
    void closesProcessesInReverseLaunchOrder() {
        RecordingLauncher launcher = new RecordingLauncher();
        ClusterLaunchPlan plan = ClusterLaunchPlan.fromManifest(
                ClusterDeploymentManifest.defaultManifest(),
                Path.of("src/main/resources"),
                "target/classes"
        );

        LocalClusterProcessGroup group = new LocalClusterProcessManager(launcher).start(plan);
        group.close();

        assertEquals(List.of("region", "scene-large", "scene-small", "game", "proxy", "center"), launcher.closed);
        assertEquals(0, group.aliveCount());
    }

    @Test
    void closesStartedProcessesWhenLaterStartFails() {
        RecordingLauncher launcher = new RecordingLauncher("game");
        ClusterLaunchPlan plan = ClusterLaunchPlan.fromManifest(
                ClusterDeploymentManifest.defaultManifest(),
                Path.of("src/main/resources"),
                "target/classes"
        );

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new LocalClusterProcessManager(launcher).start(plan));

        assertTrue(error.getMessage().contains("Failed to start local cluster"));
        assertEquals(List.of("center", "proxy", "game"), launcher.started);
        assertEquals(List.of("proxy", "center"), launcher.closed);
        assertFalse(launcher.processes.getFirst().alive());
    }

    @Test
    void waitsForEachProcessReadinessBeforeStartingNext() {
        RecordingLauncher launcher = new RecordingLauncher();
        ReadinessAfterAttempts readiness = new ReadinessAfterAttempts(Map.of(
                "center", 2,
                "game", 1,
                "scene-small", 1,
                "scene-large", 1,
                "proxy", 1,
                "region", 1
        ));
        ClusterLaunchPlan plan = ClusterLaunchPlan.fromManifest(
                ClusterDeploymentManifest.defaultManifest(),
                Path.of("src/main/resources"),
                "target/classes"
        );

        LocalClusterProcessGroup group = new LocalClusterProcessManager(
                launcher,
                readiness,
                new ClusterStartupPolicy(Duration.ofMillis(100), Duration.ofMillis(1))
        ).start(plan);

        assertEquals(6, group.processes().size());
        assertEquals(2, readiness.attempts("center"));
        assertEquals(List.of("center", "proxy", "game", "scene-small", "scene-large", "region"), launcher.started);
    }

    @Test
    void closesStartedProcessWhenReadinessTimesOut() {
        RecordingLauncher launcher = new RecordingLauncher();
        ClusterLaunchPlan plan = ClusterLaunchPlan.fromManifest(
                ClusterDeploymentManifest.defaultManifest(),
                Path.of("src/main/resources"),
                "target/classes"
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new LocalClusterProcessManager(
                launcher,
                (command, process) -> false,
                new ClusterStartupPolicy(Duration.ofMillis(5), Duration.ofMillis(1))
        ).start(plan));

        assertTrue(error.getCause().getMessage().contains("Timed out waiting for service ready: center"));
        assertEquals(List.of("center"), launcher.started);
        assertEquals(List.of("center"), launcher.closed);
    }

    private static final class RecordingLauncher implements ClusterProcessLauncher {
        private final String failOn;
        private final List<String> started = new ArrayList<>();
        private final List<String> closed = new ArrayList<>();
        private final List<RecordingProcess> processes = new ArrayList<>();

        private RecordingLauncher() {
            this("");
        }

        private RecordingLauncher(String failOn) {
            this.failOn = failOn;
        }

        @Override
        public ClusterProcess start(ClusterLaunchCommand command) {
            started.add(command.serviceName());
            if (command.serviceName().equals(failOn)) {
                throw new IllegalStateException("start failed");
            }
            RecordingProcess process = new RecordingProcess(command.serviceName(), closed);
            processes.add(process);
            return process;
        }
    }

    private static final class RecordingProcess implements ClusterProcess {
        private final String serviceName;
        private final List<String> closed;
        private boolean alive = true;

        private RecordingProcess(String serviceName, List<String> closed) {
            this.serviceName = serviceName;
            this.closed = closed;
        }

        @Override
        public String serviceName() {
            return serviceName;
        }

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public void close() {
            alive = false;
            closed.add(serviceName);
        }
    }

    private static final class ReadinessAfterAttempts implements ClusterReadinessProbe {
        private final Map<String, Integer> readyAt;
        private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

        private ReadinessAfterAttempts(Map<String, Integer> readyAt) {
            this.readyAt = readyAt;
        }

        @Override
        public boolean ready(ClusterLaunchCommand command, ClusterProcess process) {
            int current = attempts.merge(command.serviceName(), 1, Integer::sum);
            return current >= readyAt.getOrDefault(command.serviceName(), 1);
        }

        private int attempts(String serviceName) {
            return attempts.getOrDefault(serviceName, 0);
        }
    }
}
