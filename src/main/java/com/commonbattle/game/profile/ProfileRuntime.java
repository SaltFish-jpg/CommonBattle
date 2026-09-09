package com.commonbattle.game.profile;

import com.commonbattle.game.event.SubscriptionDecision;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家基础资料在非 owner 服务内的运行时门面。
 * Scene、Chat 等服务通过它维护关注、消费变更事件，并按读取模式决定是否回源修复本地快照。
 */
public final class ProfileRuntime implements ProfileInterestControl, ProfileReadPort, ProfileRuntimeView {
    private final LocalProfileCache cache;
    private final ProfileInterestControl interests;
    private final ProfileSnapshotReader reader;
    private final AtomicLong readRequests = new AtomicLong();
    private final AtomicLong localHits = new AtomicLong();
    private final AtomicLong localStale = new AtomicLong();
    private final AtomicLong localMisses = new AtomicLong();
    private final AtomicLong refreshes = new AtomicLong();
    private final AtomicLong remoteStale = new AtomicLong();
    private final AtomicLong remoteMisses = new AtomicLong();
    private final AtomicLong localFallbacks = new AtomicLong();

    public ProfileRuntime(LocalProfileCache cache, ProfileInterestControl interests, ProfileSnapshotReader reader) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.interests = Objects.requireNonNull(interests, "interests");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public void watch(long playerId) {
        interests.watch(playerId);
    }

    @Override
    public void unwatch(long playerId) {
        interests.unwatch(playerId);
    }

    @Override
    public void watchAll(Collection<Long> playerIds) {
        interests.watchAll(playerIds);
    }

    @Override
    public void unwatchAll(Collection<Long> playerIds) {
        interests.unwatchAll(playerIds);
    }

    @Override
    public void requestRepair(long playerId) {
        interests.requestRepair(playerId);
    }

    @Override
    public void requestRepairAll(Collection<Long> playerIds) {
        interests.requestRepairAll(playerIds);
    }

    public SubscriptionDecision apply(ProfileChangedEvent event) {
        return cache.apply(event);
    }

    public void refresh(PlayerProfileSnapshot snapshot) {
        cache.refresh(snapshot);
    }

    public long revisionOf(long playerId) {
        return cache.revisionOf(playerId);
    }

    @Override
    public ProfileReadResult read(long playerId, ProfileReadMode mode) {
        Objects.requireNonNull(mode, "mode");
        readRequests.incrementAndGet();
        Optional<CachedProfile> cached = cache.get(playerId);
        if (mode == ProfileReadMode.LOCAL_FAST) {
            return record(cached.map(profile -> ProfileReadResult.of(profile,
                            profile.stale() ? ProfileReadStatus.LOCAL_STALE : ProfileReadStatus.LOCAL_HIT))
                    .orElseGet(() -> ProfileReadResult.empty(ProfileReadStatus.LOCAL_MISS)));
        }
        if (mode == ProfileReadMode.REFRESH_IF_STALE && cached.filter(profile -> !profile.stale()).isPresent()) {
            return record(ProfileReadResult.of(cached.orElseThrow(), ProfileReadStatus.LOCAL_HIT));
        }
        Optional<PlayerProfileSnapshot> latest = reader.find(playerId);
        if (latest.isPresent()) {
            cache.refresh(latest.orElseThrow());
            return record(cache.get(playerId)
                    .map(profile -> ProfileReadResult.of(profile, ProfileReadStatus.REFRESHED))
                    .orElseGet(() -> ProfileReadResult.empty(ProfileReadStatus.REMOTE_MISS)));
        }
        return record(cached.map(profile -> ProfileReadResult.of(profile, ProfileReadStatus.LOCAL_FALLBACK))
                .orElseGet(() -> ProfileReadResult.empty(ProfileReadStatus.REMOTE_MISS)));
    }

    @Override
    public ProfileReadResult readAtLeast(long playerId, long minimumRevision) {
        if (minimumRevision < 0) {
            throw new IllegalArgumentException("minimumRevision must not be negative");
        }
        readRequests.incrementAndGet();
        Optional<CachedProfile> cached = cache.get(playerId);
        if (cached.filter(profile -> !profile.stale() && profile.snapshot().revision() >= minimumRevision).isPresent()) {
            return record(ProfileReadResult.of(cached.orElseThrow(), ProfileReadStatus.LOCAL_HIT));
        }
        Optional<PlayerProfileSnapshot> latest = reader.find(playerId);
        if (latest.isPresent() && latest.orElseThrow().revision() >= minimumRevision) {
            cache.refresh(latest.orElseThrow());
            return record(cache.get(playerId)
                    .filter(profile -> !profile.stale() && profile.snapshot().revision() >= minimumRevision)
                    .map(profile -> ProfileReadResult.of(profile, ProfileReadStatus.REFRESHED))
                    .orElseGet(() -> ProfileReadResult.empty(ProfileReadStatus.REMOTE_MISS)));
        }
        if (latest.isPresent()) {
            return record(cached.map(profile -> ProfileReadResult.of(profile, ProfileReadStatus.REMOTE_STALE))
                    .orElseGet(() -> ProfileReadResult.empty(ProfileReadStatus.REMOTE_STALE)));
        }
        return record(cached.map(profile -> ProfileReadResult.of(profile, ProfileReadStatus.LOCAL_FALLBACK))
                .orElseGet(() -> ProfileReadResult.empty(ProfileReadStatus.REMOTE_MISS)));
    }

    public LocalProfileCache cache() {
        return cache;
    }

    @Override
    public ProfileRuntimeStats stats() {
        return new ProfileRuntimeStats(
                readRequests.get(),
                localHits.get(),
                localStale.get(),
                localMisses.get(),
                refreshes.get(),
                remoteStale.get(),
                remoteMisses.get(),
                localFallbacks.get()
        );
    }

    private ProfileReadResult record(ProfileReadResult result) {
        switch (result.status()) {
            case LOCAL_HIT -> localHits.incrementAndGet();
            case LOCAL_STALE -> localStale.incrementAndGet();
            case LOCAL_MISS -> localMisses.incrementAndGet();
            case REFRESHED -> refreshes.incrementAndGet();
            case REMOTE_STALE -> remoteStale.incrementAndGet();
            case REMOTE_MISS -> remoteMisses.incrementAndGet();
            case LOCAL_FALLBACK -> localFallbacks.incrementAndGet();
        }
        return result;
    }
}
