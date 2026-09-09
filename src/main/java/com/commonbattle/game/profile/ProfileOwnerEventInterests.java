package com.commonbattle.game.profile;

import com.commonbattle.game.event.OwnerActorEventSubscriptionStats;
import com.commonbattle.game.event.OwnerActorEventSubscriptionView;
import com.commonbattle.game.event.OwnerEventInterestControl;

import java.util.Collection;
import java.util.Objects;

/**
 * Profile 关注控制适配器。
 * 它把业务侧的 playerId 关注映射为 profile ownerKey，底层可挂接通用 owner 事件订阅模板。
 */
public final class ProfileOwnerEventInterests implements ProfileInterestControl, ProfileInterestView {
    private volatile OwnerEventInterestControl interests;
    private volatile OwnerActorEventSubscriptionView view;

    public ProfileOwnerEventInterests() {
        this(OwnerEventInterestControl.noop(), OwnerActorEventSubscriptionStats::empty);
    }

    public ProfileOwnerEventInterests(
            OwnerEventInterestControl interests,
            OwnerActorEventSubscriptionView view
    ) {
        this.interests = Objects.requireNonNull(interests, "interests");
        this.view = Objects.requireNonNull(view, "view");
    }

    public void attach(OwnerEventInterestControl interests, OwnerActorEventSubscriptionView view) {
        this.interests = Objects.requireNonNull(interests, "interests");
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public void watch(long playerId) {
        interests.watchOwner(ProfileChangedEvent.ownerKey(playerId));
    }

    @Override
    public void unwatch(long playerId) {
        interests.unwatchOwner(ProfileChangedEvent.ownerKey(playerId));
    }

    @Override
    public void watchAll(Collection<Long> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        interests.watchOwners(playerIds.stream()
                .map(ProfileChangedEvent::ownerKey)
                .toList());
    }

    @Override
    public void unwatchAll(Collection<Long> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        interests.unwatchOwners(playerIds.stream()
                .map(ProfileChangedEvent::ownerKey)
                .toList());
    }

    @Override
    public void requestRepair(long playerId) {
        interests.requestRepairOwner(ProfileChangedEvent.ownerKey(playerId));
    }

    @Override
    public void requestRepairAll(Collection<Long> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        interests.requestRepairOwners(playerIds.stream()
                .map(ProfileChangedEvent::ownerKey)
                .toList());
    }

    @Override
    public ProfileInterestStats stats() {
        OwnerActorEventSubscriptionStats stats = view.stats();
        return new ProfileInterestStats(
                stats.watchedOwners(),
                stats.watchReferences(),
                stats.watchRequests(),
                stats.unwatchRequests(),
                stats.replayAttempts(),
                stats.replayFailures(),
                stats.repairRequests(),
                stats.repairFailures()
        );
    }
}
