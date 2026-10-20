package com.commonbattle.cluster.process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyRpcMultiProcessFailoverTest {
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(8);

    @Test
    void proxyAndSceneFailuresRecoverAcrossIndependentJvmProcesses() throws Exception {
        Ports ports = new Ports(freePort(), freePort(), freePort(), freePort());
        JvmNodeProcess game = null;
        JvmNodeProcess scene = null;
        JvmNodeProcess proxyA = null;
        JvmNodeProcess proxyB = null;
        JvmNodeProcess restartedScene = null;
        JvmNodeProcess restartedProxyB = null;
        try {
            proxyA = start("PROXY_A", ports);
            proxyB = start("PROXY_B", ports);
            scene = start("SCENE", ports);
            game = start("GAME", ports);
            awaitReady(proxyA, "PROXY_A");
            ProcessFrame proxyBReady = awaitReady(proxyB, "PROXY_B");
            ProcessFrame sceneReady = awaitReady(scene, "SCENE");
            awaitReady(game, "GAME");

            call(game, "baseline", "one", 0);
            assertSuccessInPlayerMailbox(game.awaitResult("baseline", RESULT_TIMEOUT), "echo:baseline:0:one");

            long firstProxyBPid = Long.parseLong(proxyBReady.field(2));
            proxyB.crash();
            assertFalse(proxyB.alive());
            call(game, "proxy-crash", "two", 0);
            assertSuccessInPlayerMailbox(
                    game.awaitResult("proxy-crash", RESULT_TIMEOUT), "echo:proxy-crash:0:two"
            );
            game.send("STATS|after-proxy-crash");
            ProcessFrame gameAfterProxyCrash = game.awaitForRequest(
                    "CB_STATS", "after-proxy-crash", RESULT_TIMEOUT
            );
            assertTrue(Long.parseLong(gameAfterProxyCrash.field(16)) >= 1, gameAfterProxyCrash::raw);

            restartedProxyB = start("PROXY_B", ports);
            ProcessFrame restartedProxyBReady = awaitReady(restartedProxyB, "PROXY_B");
            assertNotEquals(firstProxyBPid, Long.parseLong(restartedProxyBReady.field(2)));

            long firstScenePid = Long.parseLong(sceneReady.field(2));
            scene.crash();
            assertFalse(scene.alive());
            call(game, "scene-crash", "three", 0);
            ProcessFrame failed = game.awaitResult("scene-crash", RESULT_TIMEOUT);
            assertFailureInPlayerMailbox(failed);
            assertTrue(Long.parseLong(failed.field(7)) < 2_500,
                    () -> "forwarding failure waited for RPC timeout: " + failed.raw());

            restartedProxyB.send("STATS|scene-down");
            ProcessFrame proxyStats = restartedProxyB.awaitForRequest("CB_STATS", "scene-down", RESULT_TIMEOUT);
            assertTrue(Long.parseLong(proxyStats.field(11)) >= 1);
            assertTrue(Long.parseLong(proxyStats.field(12)) >= 1);
            assertEquals("0", proxyStats.field(18));

            restartedScene = start("SCENE", ports);
            ProcessFrame restartedSceneReady = awaitReady(restartedScene, "SCENE");
            assertNotEquals(firstScenePid, Long.parseLong(restartedSceneReady.field(2)));
            call(game, "scene-recovered", "four", 0);
            assertSuccessInPlayerMailbox(
                    game.awaitResult("scene-recovered", RESULT_TIMEOUT), "echo:scene-recovered:0:four"
            );

            call(game, "reply-failover", "five", 2_000);
            restartedScene.awaitForRequest("CB_SCENE_ACCEPTED", "reply-failover", RESULT_TIMEOUT);
            proxyA.crash();
            assertFalse(proxyA.alive());
            awaitActiveConnections(restartedScene, 1);
            ProcessFrame replyFailover = game.awaitResult("reply-failover", RESULT_TIMEOUT);
            assertSuccessInPlayerMailbox(replyFailover, "echo:reply-failover:2000:five");
            restartedScene.send("STATS|reply-route");
            ProcessFrame sceneStats = restartedScene.awaitForRequest("CB_STATS", "reply-route", RESULT_TIMEOUT);
            assertTrue(Long.parseLong(sceneStats.field(14)) >= 1,
                    () -> sceneStats.raw() + " result=" + replyFailover.raw());
            assertEquals("0", sceneStats.field(15));

            ProcessFrame finalStats = awaitQuiescentStats(game);
            assertEquals("0", finalStats.field(6));
            assertEquals("0", finalStats.field(7));
            assertEquals("0", finalStats.field(17));
        } finally {
            close(restartedScene);
            close(scene);
            close(restartedProxyB);
            close(proxyB);
            close(proxyA);
            close(game);
        }
    }

    private static ProcessFrame awaitReady(JvmNodeProcess process, String role) throws InterruptedException {
        ProcessFrame ready = process.await("CB_READY", READY_TIMEOUT);
        assertEquals(role, ready.field(1));
        return ready;
    }

    private static void awaitActiveConnections(JvmNodeProcess process, int expectedMaximum) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            String requestId = "connections-" + sequence++;
            process.send("STATS|" + requestId);
            ProcessFrame stats = process.awaitForRequest("CB_STATS", requestId, RESULT_TIMEOUT);
            if (Integer.parseInt(stats.field(3)) <= expectedMaximum) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("process did not observe proxy disconnect\n" + process.logTail());
    }

    private static ProcessFrame awaitQuiescentStats(JvmNodeProcess process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        int sequence = 0;
        ProcessFrame last = null;
        while (System.nanoTime() < deadline) {
            String requestId = "final-" + sequence++;
            process.send("STATS|" + requestId);
            last = process.awaitForRequest("CB_STATS", requestId, RESULT_TIMEOUT);
            if ("0".equals(last.field(6)) && "0".equals(last.field(7)) && "0".equals(last.field(17))) {
                return last;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("game process did not become quiescent: " + (last == null ? "" : last.raw()));
    }

    private static void call(JvmNodeProcess game, String requestId, String payload, long delayMillis)
            throws IOException {
        game.send("CALL|" + requestId + "|" + payload + "|" + delayMillis);
    }

    private static void assertSuccessInPlayerMailbox(ProcessFrame result, String response) {
        assertEquals("SUCCESS", result.field(2), result::raw);
        assertEquals(response, result.field(3));
        assertTrue(result.field(4).startsWith("common-battle-actor-"));
        assertEquals("player-10001", result.field(5));
    }

    private static void assertFailureInPlayerMailbox(ProcessFrame result) {
        assertEquals("FAILURE", result.field(2));
        assertEquals("REMOTE_UNAVAILABLE", result.field(3));
        assertEquals("ClusterForwardingException", result.field(4));
        assertTrue(result.field(5).startsWith("common-battle-actor-"));
        assertEquals("player-10001", result.field(6));
    }

    private static JvmNodeProcess start(String role, Ports ports) throws IOException {
        return JvmNodeProcess.start(
                ProxyRpcProcessNodeMain.class,
                role,
                Integer.toString(ports.game()),
                Integer.toString(ports.scene()),
                Integer.toString(ports.proxyA()),
                Integer.toString(ports.proxyB())
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

    private record Ports(int game, int scene, int proxyA, int proxyB) {
    }
}
