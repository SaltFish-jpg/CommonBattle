package com.commonbattle.game.profile;

import com.commonbattle.game.snapshot.SnapshotRepairReport;
import com.commonbattle.game.snapshot.SnapshotRepairResult;
import com.commonbattle.game.snapshot.SnapshotRepairer;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * 玩家基础资料快照修补器。
 * 当事件中心 replay 窗口不足时，它按 ownerKey 回源读取最新资料并刷新本地 ProfileCache。
 */
public final class ProfileSnapshotRepairer implements SnapshotRepairer {
    private final ProfileSnapshotReader reader;
    private final LocalProfileCache cache;

    public ProfileSnapshotRepairer(ProfileSnapshotReader reader, LocalProfileCache cache) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.cache = Objects.requireNonNull(cache, "cache");
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
                cache.refresh(snapshot.get());
                results.add(SnapshotRepairResult.refreshed(ownerKey, snapshot.get().revision()));
            } catch (RuntimeException e) {
                results.add(SnapshotRepairResult.failed(ownerKey, e.getMessage()));
            }
        }
        return new SnapshotRepairReport(results);
    }
}
