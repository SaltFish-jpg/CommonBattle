package com.commonbattle.game.profile;

import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.game.snapshot.SnapshotRepairReport;
import com.commonbattle.game.snapshot.SnapshotRepairResult;
import com.commonbattle.game.snapshot.SnapshotRepairer;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * 场景 Profile 快照修补器。
 * 它只负责回源读取最新快照，真正刷新 Scene 本地视图必须投递回 SceneProfileAwarenessAgent 的 mailbox。
 */
public final class SceneProfileSnapshotRepairer implements SnapshotRepairer {
    private final ProfileSnapshotReader reader;
    private final SceneProfileAwarenessAgent scene;

    public SceneProfileSnapshotRepairer(ProfileSnapshotReader reader, SceneProfileAwarenessAgent scene) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    @Override
    public SnapshotRepairReport repair(Set<String> ownerKeys) {
        ArrayList<SnapshotRepairResult> results = new ArrayList<>();
        for (String ownerKey : ownerKeys) {
            var parsed = ProfileOwnerKeyParser.INSTANCE.parse(ownerKey);
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
                results.add(SnapshotRepairResult.refreshed(ownerKey, snapshot.get().revision()));
            } catch (RuntimeException e) {
                results.add(SnapshotRepairResult.failed(ownerKey, e.getMessage()));
            }
        }
        return new SnapshotRepairReport(results);
    }
}
