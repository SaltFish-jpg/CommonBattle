package com.commonbattle.game.social;

import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.snapshot.SnapshotRepairReport;
import com.commonbattle.game.snapshot.SnapshotRepairResult;
import com.commonbattle.game.snapshot.SnapshotRepairer;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * 联盟关系快照修补器。
 * 它回源读取联盟成员快照，再投递给 SceneAllianceAwarenessAgent 的 mailbox 刷新本地关系视图。
 */
public final class AllianceSnapshotRepairer implements SnapshotRepairer {
    private final AllianceSnapshotReader reader;
    private final SceneAllianceAwarenessAgent scene;

    public AllianceSnapshotRepairer(AllianceSnapshotReader reader, SceneAllianceAwarenessAgent scene) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    @Override
    public SnapshotRepairReport repair(Set<String> ownerKeys) {
        ArrayList<SnapshotRepairResult> results = new ArrayList<>();
        for (String ownerKey : ownerKeys) {
            var parsed = AllianceOwnerKeyParser.INSTANCE.parse(ownerKey);
            if (parsed.isEmpty()) {
                results.add(SnapshotRepairResult.invalidOwnerKey(ownerKey));
                continue;
            }
            long allianceId = parsed.getAsLong();
            try {
                var snapshot = reader.find(allianceId);
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
