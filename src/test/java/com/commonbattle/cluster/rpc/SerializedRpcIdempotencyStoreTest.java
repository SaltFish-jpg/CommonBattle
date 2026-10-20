package com.commonbattle.cluster.rpc;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedRpcIdempotencyStoreTest {
    private final InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RpcIdempotencyKey key = new RpcIdempotencyKey(
            ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
            "scene.reserve",
            "player-1-room-9"
    );

    @Test
    void coordinatesClaimAndCompletedResultAcrossStoreInstances() {
        SerializedRpcIdempotencyStore first = store();
        SerializedRpcIdempotencyStore second = store();

        assertEquals(RpcIdempotencyClaim.Status.ACQUIRED, first.claim(key, "scene-1:req-1").status());
        assertEquals(RpcIdempotencyClaim.Status.IN_PROGRESS, second.claim(key, "scene-2:req-2").status());
        assertTrue(first.renew(key, "scene-1:req-1"));
        assertTrue(first.complete(key, "scene-1:req-1", RpcIdempotencyResult.success("seat-1")));

        RpcIdempotencyClaim completed = second.claim(key, "scene-2:req-2");
        assertEquals(RpcIdempotencyClaim.Status.COMPLETED, completed.status());
        assertTrue(completed.result().success());
        assertEquals("seat-1", completed.result().payload());
        assertEquals(1, second.size());
    }

    @Test
    void expiredLeaseCanBeTakenOverByAnotherInstance() {
        SerializedRpcIdempotencyStore first = store();
        SerializedRpcIdempotencyStore second = store();
        assertEquals(RpcIdempotencyClaim.Status.ACQUIRED, first.claim(key, "scene-1:req-1").status());

        clock.advance(Duration.ofSeconds(6));

        assertEquals(RpcIdempotencyClaim.Status.ACQUIRED, second.claim(key, "scene-2:req-2").status());
        assertFalse(first.renew(key, "scene-1:req-1"));
        assertFalse(first.complete(key, "scene-1:req-1", RpcIdempotencyResult.success("stale")));
        assertTrue(second.complete(key, "scene-2:req-2", RpcIdempotencyResult.success("seat-2")));
        assertEquals("seat-2", first.get(key).payload());
    }

    @Test
    void releaseAllowsImmediateRetryWithoutWaitingForLeaseExpiry() {
        SerializedRpcIdempotencyStore first = store();
        SerializedRpcIdempotencyStore second = store();
        first.claim(key, "scene-1:req-1");

        assertFalse(first.release(key, "wrong-owner"));
        assertTrue(first.release(key, "scene-1:req-1"));
        assertEquals(RpcIdempotencyClaim.Status.ACQUIRED, second.claim(key, "scene-2:req-2").status());
    }

    @Test
    void completedResultExpiresAndCanBeExecutedAgain() {
        SerializedRpcIdempotencyStore first = store();
        SerializedRpcIdempotencyStore recovered = store();
        first.claim(key, "scene-1:req-1");
        first.complete(key, "scene-1:req-1", RpcIdempotencyResult.failure("room-full"));

        assertEquals("room-full", recovered.get(key).payload());
        clock.advance(Duration.ofMinutes(2));

        assertNull(recovered.get(key));
        assertEquals(0, recovered.size());
        assertEquals(RpcIdempotencyClaim.Status.ACQUIRED,
                recovered.claim(key, "scene-2:req-2").status());
    }

    private SerializedRpcIdempotencyStore store() {
        return new SerializedRpcIdempotencyStore(
                bytes,
                PayloadCodecRegistry.commonDefaults(),
                Duration.ofMinutes(1),
                Duration.ofSeconds(5),
                clock
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
