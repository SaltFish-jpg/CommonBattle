package com.commonbattle.cluster.process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryCenterRestartMultiProcessTest {
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(8);

    @Test
    void centerCrashResetsVersionAndRemoteNodesReRegisterAndRecoverSubscriptions() throws Exception {
        int centerPort = freePort();
        int watcherPort = freePort();
        int scenePort = freePort();
        JvmNodeProcess center = null;
        JvmNodeProcess restartedCenter = null;
        JvmNodeProcess watcher = null;
        JvmNodeProcess scene = null;
        try {
            center = start("CENTER", centerPort, centerPort);
            ProcessFrame firstCenterReady = center.await("CB_READY", FRAME_TIMEOUT);
            watcher = start("WATCHER", centerPort, watcherPort);
            watcher.await("CB_READY", FRAME_TIMEOUT);
            scene = start("SCENE", centerPort, scenePort);
            scene.await("CB_READY", FRAME_TIMEOUT);

            ProcessFrame firstRegistered = watcher.await(
                    frame -> registryEvent(frame, "REGISTERED"), FRAME_TIMEOUT);
            assertTrue(firstRegistered.field(2).contains("scene-recovery"));
            awaitWatcherSceneCount(watcher, 1, Duration.ofSeconds(5));

            long firstCenterPid = Long.parseLong(firstCenterReady.field(2));
            center.crash();
            assertFalse(center.alive());
            awaitRecoveryFailure(watcher, Duration.ofSeconds(5));

            restartedCenter = start("CENTER", centerPort, centerPort);
            ProcessFrame restartedReady = restartedCenter.await("CB_READY", FRAME_TIMEOUT);
            assertNotEquals(firstCenterPid, Long.parseLong(restartedReady.field(2)));

            awaitWatcherSceneCount(watcher, 0, Duration.ofSeconds(8));
            ProcessFrame removedAfterVersionReset = watcher.await(
                    frame -> registryEvent(frame, "UNREGISTERED"), FRAME_TIMEOUT);
            assertTrue(removedAfterVersionReset.field(2).contains("scene-recovery"));
            ProcessFrame registeredAfterLeaseRepair = watcher.await(
                    frame -> registryEvent(frame, "REGISTERED"), Duration.ofSeconds(12));
            assertTrue(registeredAfterLeaseRepair.field(2).contains("scene-recovery"));

            ProcessFrame watcherStats = awaitWatcherRecovered(watcher, Duration.ofSeconds(6));
            assertTrue(Long.parseLong(watcherStats.field(6)) > 0);
            assertTrue(Long.parseLong(watcherStats.field(8)) > 0);
            assertTrue(Long.parseLong(watcherStats.field(10)) > 0);

            ProcessFrame sceneStats = awaitReregistration(scene, Duration.ofSeconds(6));
            assertTrue(Long.parseLong(sceneStats.field(10)) > 0);

            ProcessFrame centerStats = awaitCenterRecovered(restartedCenter, Duration.ofSeconds(6));
            assertEquals("1", centerStats.field(2));
            assertEquals("1", centerStats.field(3));
            assertEquals("1", centerStats.field(4));
            assertEquals("1", centerStats.field(5));
        } finally {
            close(scene);
            close(watcher);
            close(restartedCenter);
            close(center);
        }
    }

    private static boolean registryEvent(ProcessFrame frame, String eventType) {
        return "CB_REGISTRY_EVENT".equals(frame.field(0))
                && eventType.equals(frame.field(1))
                && frame.field(2).contains("scene-recovery");
    }

    private static ProcessFrame awaitWatcherSceneCount(
            JvmNodeProcess watcher,
            int expected,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            ProcessFrame stats = stats(watcher, "watch-scenes-" + sequence++);
            if (Integer.toString(expected).equals(stats.field(3))) {
                return stats;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("watcher scene count did not become " + expected + "\n" + watcher.logTail());
    }

    private static void awaitRecoveryFailure(JvmNodeProcess watcher, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            ProcessFrame stats = stats(watcher, "watch-failure-" + sequence++);
            if (Long.parseLong(stats.field(7)) > 0 && Long.parseLong(stats.field(9)) > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("watcher did not observe center outage\n" + watcher.logTail());
    }

    private static ProcessFrame awaitWatcherRecovered(
            JvmNodeProcess watcher,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            ProcessFrame stats = stats(watcher, "watch-recovered-" + sequence++);
            if ("1".equals(stats.field(3))
                    && Long.parseLong(stats.field(6)) > 0
                    && Long.parseLong(stats.field(8)) > 0
                    && Long.parseLong(stats.field(10)) > 0) {
                return stats;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("watcher metrics did not converge after recovery\n" + watcher.logTail());
    }

    private static ProcessFrame awaitReregistration(
            JvmNodeProcess process,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            ProcessFrame stats = stats(process, "reregistered-" + sequence++);
            if (Long.parseLong(stats.field(10)) > 0) {
                return stats;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("node did not report re-registration\n" + process.logTail());
    }

    private static ProcessFrame awaitCenterRecovered(
            JvmNodeProcess center,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            String requestId = "center-recovered-" + sequence++;
            center.send("STATS|" + requestId);
            ProcessFrame stats = center.awaitForRequest("CB_CENTER_STATS", requestId, FRAME_TIMEOUT);
            if ("1".equals(stats.field(2))
                    && "1".equals(stats.field(3))
                    && "1".equals(stats.field(4))
                    && "1".equals(stats.field(5))) {
                return stats;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("center registry did not recover\n" + center.logTail());
    }

    private static ProcessFrame stats(JvmNodeProcess process, String requestId) throws Exception {
        process.send("STATS|" + requestId);
        return process.awaitForRequest("CB_REGISTRY_STATS", requestId, FRAME_TIMEOUT);
    }

    private static JvmNodeProcess start(String role, int centerPort, int localPort) throws IOException {
        return JvmNodeProcess.start(
                RegistryRecoveryProcessNodeMain.class,
                role,
                Integer.toString(centerPort),
                Integer.toString(localPort)
        );
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void close(JvmNodeProcess process) {
        if (process != null) {
            process.close();
        }
    }
}
