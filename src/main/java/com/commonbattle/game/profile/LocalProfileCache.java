package com.commonbattle.game.profile;

import com.commonbattle.game.event.SubscriptionCheckpoint;
import com.commonbattle.game.event.SubscriptionDecision;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个 Game、Scene、Chat 进程内的玩家资料本地缓存。
 * 它通过 ProfileChangedEvent 更新，普通展示读本地，stale 或强一致读再回 Profile owner。
 */
public final class LocalProfileCache {
    private final Map<Long, CachedProfile> profiles = new ConcurrentHashMap<>();
    private final SubscriptionCheckpoint checkpoint = new SubscriptionCheckpoint();
    private final ProfileProjectionStore store;

    public LocalProfileCache() {
        this(new SerializedProfileProjectionStore(new InMemoryProfileProjectionBytesStore(), "default"));
    }

    public LocalProfileCache(ProfileProjectionStore store) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        for (CachedProfile profile : store.loadAll()) {
            PlayerProfileSnapshot snapshot = profile.snapshot();
            CachedProfile current = profiles.get(snapshot.playerId());
            if (current == null || current.snapshot().revision() < snapshot.revision()) {
                profiles.put(snapshot.playerId(), profile);
                checkpoint.reset(ProfileChangedEvent.ownerKey(snapshot.playerId()), snapshot.revision());
            }
        }
    }

    public synchronized SubscriptionDecision apply(ProfileChangedEvent event) {
        SubscriptionDecision decision = checkpoint.inspect(event);
        if (decision == SubscriptionDecision.DUPLICATE_OR_OLD) {
            return decision;
        }
        boolean stale = decision == SubscriptionDecision.GAP;
        // Profile cache 更新边界：事件按 revision 去重后才更新本地快照；跳号快照可展示但必须标脏。
        CachedProfile updated = new CachedProfile(event.snapshot(), stale);
        // 投影提交边界：完整快照与 revision 先原子落盘，成功后才推进内存 checkpoint。
        store.save(updated);
        profiles.put(event.playerId(), updated);
        checkpoint.markApplied(event);
        return decision;
    }

    public Optional<CachedProfile> get(long playerId) {
        return Optional.ofNullable(profiles.get(playerId));
    }

    public boolean isStale(long playerId) {
        return get(playerId).map(CachedProfile::stale).orElse(true);
    }

    public synchronized void refresh(PlayerProfileSnapshot snapshot) {
        long currentRevision = revisionOf(snapshot.playerId());
        if (snapshot.revision() < currentRevision) {
            return;
        }
        CachedProfile updated = new CachedProfile(snapshot, false);
        store.save(updated);
        profiles.put(snapshot.playerId(), updated);
        checkpoint.markApplied(new ProfileChangedEvent(snapshot.playerId(), SetOf.all(), snapshot));
    }

    public Optional<CachedProfile> refreshFrom(ProfileSnapshotReader reader, long playerId) {
        return reader.find(playerId).map(snapshot -> {
            refresh(snapshot);
            return profiles.get(playerId);
        });
    }

    public long revisionOf(long playerId) {
        return checkpoint.revisionOf(ProfileChangedEvent.ownerKey(playerId));
    }

    public Map<String, Long> knownRevisions() {
        return checkpoint.snapshot();
    }

    private static final class SetOf {
        private static java.util.Set<ProfileField> all() {
            return java.util.Set.of(ProfileField.values());
        }
    }
}
