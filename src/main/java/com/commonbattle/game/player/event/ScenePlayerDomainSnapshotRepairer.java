package com.commonbattle.game.player.event;

import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.snapshot.SnapshotRepairReport;
import com.commonbattle.game.snapshot.SnapshotRepairResult;
import com.commonbattle.game.snapshot.SnapshotRepairer;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * Scene 玩家领域投影快照修补器。
 * 它回源读取可快照化投影，再投递回 ScenePlayerDomainEventAgent 的 mailbox 重建本地视图。
 */
public final class ScenePlayerDomainSnapshotRepairer implements SnapshotRepairer {
    private final PlayerDomainProjectionSnapshotReader reader;
    private final ScenePlayerDomainEventAgent scene;

    public ScenePlayerDomainSnapshotRepairer(
            PlayerDomainProjectionSnapshotReader reader,
            ScenePlayerDomainEventAgent scene
    ) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    @Override
    public SnapshotRepairReport repair(Set<String> ownerKeys) {
        ArrayList<SnapshotRepairResult> results = new ArrayList<>();
        for (String ownerKey : ownerKeys) {
            var parsed = PlayerDomainOwnerKeyParser.INSTANCE.parse(ownerKey);
            if (parsed.isEmpty()) {
                results.add(SnapshotRepairResult.invalidOwnerKey(ownerKey));
                continue;
            }
            long playerId = parsed.getAsLong();
            try {
                var snapshot = reader.find(playerId);
                if (snapshot.isEmpty()) {
                    results.add(SnapshotRepairResult.missing(ownerKey));
                    continue;
                }
                scene.refresh(snapshot.get());
                results.add(SnapshotRepairResult.refreshed(ownerKey, snapshot.get().eventRevision()));
            } catch (RuntimeException e) {
                results.add(SnapshotRepairResult.failed(ownerKey, e.getMessage()));
            }
        }
        return new SnapshotRepairReport(results);
    }
}
