package com.commonbattle.game.scene;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景玩家可见数据订阅协调器。
 * 它把玩家进入/离开的生命周期同步到 profile、friend、玩家领域事件和 alliance 感知 Agent。
 */
public final class ScenePlayerInterestCoordinator implements SceneRuntimeView {
    private final SceneProfileAwarenessAgent profiles;
    private final SceneFriendAwarenessAgent friends;
    private final ScenePlayerDomainEventAgent domainEvents;
    private final SceneAllianceAwarenessAgent alliances;
    private final Map<Long, Map<String, Long>> playerInterests = new HashMap<>();
    private final AtomicLong enters = new AtomicLong();
    private final AtomicLong duplicateEnters = new AtomicLong();
    private final AtomicLong leaves = new AtomicLong();
    private final AtomicLong missingLeaves = new AtomicLong();
    private final AtomicLong allianceSwitches = new AtomicLong();

    public ScenePlayerInterestCoordinator(
            SceneProfileAwarenessAgent profiles,
            SceneFriendAwarenessAgent friends,
            ScenePlayerDomainEventAgent domainEvents,
            SceneAllianceAwarenessAgent alliances
    ) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.friends = Objects.requireNonNull(friends, "friends");
        this.domainEvents = Objects.requireNonNull(domainEvents, "domainEvents");
        this.alliances = Objects.requireNonNull(alliances, "alliances");
    }

    public synchronized void enter(ScenePlayerInterest interest) {
        Objects.requireNonNull(interest, "interest");
        Map<String, Long> interests = playerInterests.computeIfAbsent(interest.playerId(), ignored -> new HashMap<>());
        Long previousAlliance = interests.putIfAbsent(interest.interestKey(), interest.allianceId());
        if (previousAlliance != null) {
            duplicateEnters.incrementAndGet();
            if (previousAlliance != interest.allianceId()) {
                switchAlliance(interest, previousAlliance);
            }
            return;
        }
        enters.incrementAndGet();
        if (interests.size() == 1) {
            friends.enter(interest.playerId());
            domainEvents.enter(interest.playerId());
            alliances.enter(interest.playerId());
        }
        profiles.enter(interest.playerId(), interest.interestKey());
        if (interest.hasAlliance()) {
            alliances.watchAlliance(interest.allianceId());
        }
    }

    public synchronized void leave(long playerId, String interestKey) {
        Objects.requireNonNull(interestKey, "interestKey");
        Map<String, Long> interests = playerInterests.get(playerId);
        if (interests == null) {
            missingLeaves.incrementAndGet();
            return;
        }
        Long allianceId = interests.remove(interestKey);
        if (allianceId == null) {
            missingLeaves.incrementAndGet();
            return;
        }
        leaves.incrementAndGet();
        profiles.leave(playerId, interestKey);
        if (allianceId > 0) {
            alliances.unwatchAlliance(allianceId);
        }
        if (interests.isEmpty()) {
            playerInterests.remove(playerId);
            friends.leave(playerId);
            domainEvents.leave(playerId);
            alliances.leave(playerId);
        }
    }

    public synchronized void leave(ScenePlayerInterest interest) {
        Objects.requireNonNull(interest, "interest");
        leave(interest.playerId(), interest.interestKey());
    }

    public synchronized ScenePlayerInterestStats interestStats() {
        int interests = 0;
        int allianceReferences = 0;
        for (Map<String, Long> player : playerInterests.values()) {
            interests += player.size();
            for (long allianceId : player.values()) {
                if (allianceId > 0) {
                    allianceReferences++;
                }
            }
        }
        return new ScenePlayerInterestStats(
                playerInterests.size(),
                interests,
                allianceReferences,
                enters.get(),
                duplicateEnters.get(),
                leaves.get(),
                missingLeaves.get(),
                allianceSwitches.get()
        );
    }

    @Override
    public synchronized SceneRuntimeStats stats() {
        ScenePlayerInterestStats stats = interestStats();
        SceneProjectionStats projection = friends.projectionStats().plus(alliances.projectionStats());
        return new SceneRuntimeStats(
                0,
                stats.players(),
                0,
                0,
                stats.interests(),
                stats.allianceReferences(),
                stats.duplicateEnters(),
                stats.missingLeaves(),
                projection.receivedEvents(),
                projection.appliedEvents(),
                projection.duplicateEvents(),
                projection.gapEvents(),
                projection.repairRequests(),
                projection.appliedSnapshots(),
                projection.ignoredSnapshots(),
                projection.staleViews()
        );
    }

    private void switchAlliance(ScenePlayerInterest interest, long previousAlliance) {
        Map<String, Long> interests = playerInterests.get(interest.playerId());
        interests.put(interest.interestKey(), interest.allianceId());
        if (previousAlliance > 0) {
            alliances.unwatchAlliance(previousAlliance);
        }
        if (interest.hasAlliance()) {
            alliances.watchAlliance(interest.allianceId());
        }
        allianceSwitches.incrementAndGet();
    }
}
