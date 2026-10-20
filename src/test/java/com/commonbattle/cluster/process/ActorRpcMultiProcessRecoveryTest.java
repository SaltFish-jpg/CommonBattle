package com.commonbattle.cluster.process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorRpcMultiProcessRecoveryTest {
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(6);

    @Test
    void sceneProcessCrashAndSamePortRestartPreserveActorRpcBoundaries() throws Exception {
        int gamePort = freePort();
        int scenePort = freePort();
        JvmNodeProcess game = null;
        JvmNodeProcess scene = null;
        JvmNodeProcess restartedScene = null;
        try {
            game = start("GAME", gamePort, scenePort);
            ProcessFrame gameReady = game.await("CB_READY", READY_TIMEOUT);
            assertEquals("GAME", gameReady.field(1));

            game.send("CALL|before-start|one");
            assertFailureInPlayerMailbox(game.awaitResult("before-start", RESULT_TIMEOUT));

            scene = start("SCENE", gamePort, scenePort);
            ProcessFrame firstSceneReady = scene.await("CB_READY", READY_TIMEOUT);
            game.send("CALL|before-crash|two");
            assertSceneMailbox(scene.awaitForRequest("CB_SCENE_HANDLED", "before-crash", RESULT_TIMEOUT));
            assertSuccessInPlayerMailbox(
                    game.awaitResult("before-crash", RESULT_TIMEOUT),
                    "echo:before-crash:two"
            );

            long firstScenePid = Long.parseLong(firstSceneReady.field(2));
            scene.crash();
            assertFalse(scene.alive());

            awaitNoActiveConnection(game);
            game.send("CALL|during-crash|three");
            assertFailureInPlayerMailbox(game.awaitResult("during-crash", RESULT_TIMEOUT));

            restartedScene = start("SCENE", gamePort, scenePort);
            ProcessFrame restartedReady = restartedScene.await("CB_READY", READY_TIMEOUT);
            assertNotEquals(firstScenePid, Long.parseLong(restartedReady.field(2)));
            game.send("CALL|after-restart|four");
            assertSceneMailbox(restartedScene.awaitForRequest(
                    "CB_SCENE_HANDLED", "after-restart", RESULT_TIMEOUT));
            assertSuccessInPlayerMailbox(
                    game.awaitResult("after-restart", RESULT_TIMEOUT),
                    "echo:after-restart:four"
            );

            game.send("STATS|final");
            ProcessFrame stats = game.awaitForRequest("CB_STATS", "final", RESULT_TIMEOUT);
            assertEquals("1", stats.field(2));
            assertTrue(Long.parseLong(stats.field(3)) >= 3);
            assertEquals("0", stats.field(5));
            assertEquals("0", stats.field(6));
        } finally {
            close(restartedScene);
            close(scene);
            close(game);
        }
    }

    private static void assertSuccessInPlayerMailbox(ProcessFrame result, String response) {
        assertEquals("SUCCESS", result.field(2));
        assertEquals(response, result.field(3));
        assertTrue(result.field(4).startsWith("common-battle-actor-"));
        assertEquals("player-10001", result.field(5));
    }

    private static void assertFailureInPlayerMailbox(ProcessFrame result) {
        assertEquals("FAILURE", result.field(2));
        assertEquals("REMOTE_UNAVAILABLE", result.field(3));
        assertTrue(result.field(5).startsWith("common-battle-actor-"));
        assertEquals("player-10001", result.field(6));
    }

    private static void assertSceneMailbox(ProcessFrame handled) {
        assertTrue(handled.field(2).startsWith("common-battle-actor-"));
        assertEquals("scene-process-agent", handled.field(3));
    }

    private static void awaitNoActiveConnection(JvmNodeProcess game) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            String requestId = "disconnect-" + sequence++;
            game.send("STATS|" + requestId);
            if ("0".equals(game.awaitForRequest("CB_STATS", requestId, RESULT_TIMEOUT).field(2))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("game process did not observe scene disconnection\n" + game.logTail());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static JvmNodeProcess start(String role, int gamePort, int scenePort) throws IOException {
        return JvmNodeProcess.start(
                ActorRpcProcessNodeMain.class,
                role,
                Integer.toString(gamePort),
                Integer.toString(scenePort)
        );
    }

    private static void close(JvmNodeProcess process) {
        if (process != null) {
            process.close();
        }
    }

}
