package com.commonbattle.game.profile;

import com.commonbattle.game.event.OwnerActorEventSubscriptionStats;
import com.commonbattle.game.event.OwnerActorEventSubscriptionView;
import com.commonbattle.game.event.OwnerEventInterestControl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileOwnerEventInterestsTest {
    @Test
    void mapsPlayerIdsToProfileOwnerKeysWithoutCollapsingDuplicates() {
        RecordingOwnerInterests ownerInterests = new RecordingOwnerInterests();
        ProfileOwnerEventInterests interests = new ProfileOwnerEventInterests(
                ownerInterests,
                new FixedView(new OwnerActorEventSubscriptionStats(1, 2, 2, 1, 1, 0, 1, 0, 1, 1, 0))
        );

        interests.watchAll(Arrays.asList(10001L, 10001L));
        interests.unwatch(10001L);
        interests.requestRepairAll(List.of(10001L, 20002L));

        assertEquals(List.of(ProfileChangedEvent.ownerKey(10001L), ProfileChangedEvent.ownerKey(10001L)),
                ownerInterests.watched);
        assertEquals(List.of(ProfileChangedEvent.ownerKey(10001L)), ownerInterests.unwatched);
        assertEquals(List.of(ProfileChangedEvent.ownerKey(10001L), ProfileChangedEvent.ownerKey(20002L)),
                ownerInterests.repairs);

        ProfileInterestStats stats = interests.stats();
        assertEquals(1, stats.watchedOwners());
        assertEquals(2, stats.watchReferences());
        assertEquals(2, stats.watchRequests());
        assertEquals(1, stats.unwatchRequests());
        assertEquals(1, stats.replayAttempts());
        assertEquals(1, stats.repairRequests());
    }

    private record FixedView(OwnerActorEventSubscriptionStats stats) implements OwnerActorEventSubscriptionView {
    }

    private static final class RecordingOwnerInterests implements OwnerEventInterestControl {
        private final List<String> watched = new ArrayList<>();
        private final List<String> unwatched = new ArrayList<>();
        private final List<String> repairs = new ArrayList<>();

        @Override
        public void watchOwner(String ownerKey) {
            watched.add(ownerKey);
        }

        @Override
        public void unwatchOwner(String ownerKey) {
            unwatched.add(ownerKey);
        }

        @Override
        public void watchOwners(Collection<String> ownerKeys) {
            watched.addAll(ownerKeys);
        }

        @Override
        public void requestRepairOwners(Collection<String> ownerKeys) {
            repairs.addAll(ownerKeys);
        }
    }
}
