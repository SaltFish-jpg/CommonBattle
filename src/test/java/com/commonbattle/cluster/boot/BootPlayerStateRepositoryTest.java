package com.commonbattle.cluster.boot;

import com.commonbattle.game.player.PlayerProfile;
import com.commonbattle.game.player.PlayerStateRepository;
import com.commonbattle.game.player.PlayerStateSnapshot;
import com.commonbattle.game.player.PlayerCheckpointRepository;
import com.commonbattle.game.player.PlayerBusinessResponse;
import com.commonbattle.game.player.PlayerBusinessResponseSnapshot;
import com.commonbattle.game.player.PlayerBusinessAck;
import com.commonbattle.game.player.ActivityProgressCommand;
import com.commonbattle.game.session.PlayerCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BootPlayerStateRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void configureFileRepositoryCanRecoverSnapshotAfterRebuild() {
        Properties properties = base();
        properties.setProperty("cluster.player.state.store", "FILE");
        properties.setProperty("cluster.player.state.store.dir", directory.toString());
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
        PlayerStateSnapshot snapshot = new PlayerProfile(10001L, Instant.parse("2026-08-01T00:00:00Z"))
                .snapshot(7, 3, Instant.parse("2026-09-01T00:00:00Z"));

        PlayerStateRepository first = BootPlayerStateRepository.configure(new BootRuntime(), config);
        first.save(10001L, snapshot);
        PlayerStateRepository rebuilt = BootPlayerStateRepository.configure(new BootRuntime(), config);

        assertEquals(snapshot, rebuilt.load(10001L).orElseThrow());
    }

    @Test
    void fileMutationRecordRecoversCompletedResponseAfterRebuild() {
        Properties properties = base();
        properties.setProperty("cluster.player.state.store", "FILE");
        properties.setProperty("cluster.player.state.store.dir", directory.toString());
        ClusterNodeConfig config = ClusterNodeConfig.fromProperties(properties);
        long playerId = 10001L;
        PlayerStateSnapshot snapshot = new PlayerProfile(playerId, Instant.parse("2026-08-01T00:00:00Z"))
                .snapshot(1, 0, Instant.parse("2026-09-01T00:00:00Z"));
        ActivityProgressCommand payload = new ActivityProgressCommand("battle-win-1", 1);
        PlayerCommand command = new PlayerCommand(
                playerId, "session-1", 1, 1, payload.operation(), payload
        );
        PlayerBusinessResponse response = PlayerBusinessResponse.success(command, PlayerBusinessAck.OK);
        PlayerCheckpointRepository first = (PlayerCheckpointRepository)
                BootPlayerStateRepository.configure(new BootRuntime(), config);
        first.save(playerId, snapshot);
        first.responses().save(new PlayerBusinessResponseSnapshot(playerId, List.of(response)));

        PlayerCheckpointRepository rebuilt = (PlayerCheckpointRepository)
                BootPlayerStateRepository.configure(new BootRuntime(), config);

        assertEquals(List.of(response), rebuilt.responses().load(playerId).orElseThrow().responses());
        assertEquals(snapshot, rebuilt.load(playerId).orElseThrow());
    }

    private static Properties base() {
        Properties properties = new Properties();
        properties.setProperty("cluster.kind", "GAME");
        properties.setProperty("cluster.region", "r1");
        properties.setProperty("cluster.node", "game-1");
        properties.setProperty("cluster.host", "127.0.0.1");
        properties.setProperty("cluster.port", "9001");
        properties.setProperty("cluster.center.host", "127.0.0.1");
        properties.setProperty("cluster.center.port", "9000");
        properties.setProperty("cluster.actor.workers", "4");
        return properties;
    }
}
