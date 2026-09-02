package com.commonbattle.game.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.game.profile.ProfileInterestControl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneProfileAwarenessAgentTest {
    @Test
    void sceneUpdatesLocalProfileCacheForOnlinePlayer() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneProfileAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onProfileChanged(event(10001L, 1, "hero", "avatar_2"));
        executor.runNext();

        PlayerProfileSnapshot snapshot = scene.profileOf(10001L).orElseThrow().snapshot();
        assertEquals("hero", snapshot.name());
        assertEquals("avatar_2", snapshot.appearance().avatar());
        assertFalse(scene.profileOf(10001L).orElseThrow().stale());
    }

    @Test
    void sceneIgnoresProfileEventForOfflinePlayer() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneProfileAwarenessAgent scene = createScene(executor);

        scene.onProfileChanged(event(10001L, 1, "hero", "avatar_2"));
        executor.runNext();

        assertTrue(scene.profileOf(10001L).isEmpty());
    }

    @Test
    void sceneMarksProfileCacheStaleWhenEventRevisionHasGap() {
        RecordingExecutor executor = new RecordingExecutor();
        SceneProfileAwarenessAgent scene = createScene(executor);

        scene.enter(10001L);
        executor.runNext();
        scene.onProfileChanged(event(10001L, 3, "hero", "avatar_2"));
        executor.runNext();

        assertTrue(scene.profileOf(10001L).orElseThrow().stale());
        assertEquals(3, scene.profileOf(10001L).orElseThrow().snapshot().revision());
    }

    @Test
    void enterAndLeaveDriveProfileInterestLifecycle() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingInterestControl interests = new RecordingInterestControl();
        SceneProfileAwarenessAgent scene = createScene(executor, interests);

        scene.enter(10001L);
        executor.runNext();
        scene.enter(10001L);
        executor.runNext();
        scene.leave(10001L);
        executor.runNext();
        scene.leave(10001L);
        executor.runNext();

        assertEquals(List.of(10001L), interests.watched);
        assertEquals(List.of(10001L), interests.unwatched);
    }

    private static SceneProfileAwarenessAgent createScene(Executor executor) {
        return createScene(executor, ProfileInterestControl.noop());
    }

    private static SceneProfileAwarenessAgent createScene(Executor executor, ProfileInterestControl interests) {
        ActorSystem actors = new ActorSystem(executor, 64);
        return new SceneProfileAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-profile"),
                interests
        );
    }

    private static ProfileChangedEvent event(long playerId, long revision, String name, String avatar) {
        return new ProfileChangedEvent(
                playerId,
                Set.of(ProfileField.NAME, ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        playerId,
                        name,
                        10,
                        new AppearanceSummary(avatar, "frame_1", "costume_1"),
                        new AllianceBrief(100, "alliance", "badge"),
                        new FriendBrief(20, 7),
                        revision,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static final class RecordingInterestControl implements ProfileInterestControl {
        private final List<Long> watched = new ArrayList<>();
        private final List<Long> unwatched = new ArrayList<>();

        @Override
        public void watch(long playerId) {
            watched.add(playerId);
        }

        @Override
        public void unwatch(long playerId) {
            unwatched.add(playerId);
        }
    }
}
