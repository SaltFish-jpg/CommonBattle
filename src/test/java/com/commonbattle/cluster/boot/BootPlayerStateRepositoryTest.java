package com.commonbattle.cluster.boot;

import com.commonbattle.game.player.PlayerProfile;
import com.commonbattle.game.player.PlayerStateRepository;
import com.commonbattle.game.player.PlayerStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;

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
