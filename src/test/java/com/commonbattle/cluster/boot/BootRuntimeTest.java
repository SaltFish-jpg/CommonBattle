package com.commonbattle.cluster.boot;

import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.runtime.DrainableComponent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BootRuntimeTest {
    @Test
    void closesResourcesInReverseOrder() {
        BootRuntime runtime = new BootRuntime();
        List<String> closed = new ArrayList<>();

        runtime.add("first", () -> closed.add("first"));
        runtime.add("second", () -> closed.add("second"));
        runtime.add("third", () -> closed.add("third"));

        runtime.close();

        assertEquals(List.of("third", "second", "first"), closed);
    }

    @Test
    void continuesClosingAndSuppressesLaterFailures() {
        BootRuntime runtime = new BootRuntime();
        List<String> closed = new ArrayList<>();

        runtime.add("first", () -> {
            closed.add("first");
            throw new IllegalStateException("first failed");
        });
        runtime.add("second", () -> {
            closed.add("second");
            throw new IllegalStateException("second failed");
        });
        runtime.add("third", () -> closed.add("third"));

        RuntimeException error = assertThrows(RuntimeException.class, runtime::close);

        assertEquals(List.of("third", "second", "first"), closed);
        assertEquals("Failed to close boot resource: second", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("Failed to close boot resource: first", error.getSuppressed()[0].getMessage());
    }

    @Test
    void addingAfterCloseImmediatelyClosesResource() {
        BootRuntime runtime = new BootRuntime();
        List<String> closed = new ArrayList<>();

        runtime.close();
        AutoCloseable closeable = () -> closed.add("late");
        AutoCloseable returned = runtime.add("late", closeable);

        assertSame(closeable, returned);
        assertEquals(List.of("late"), closed);
    }

    @Test
    void closeIsIdempotent() {
        BootRuntime runtime = new BootRuntime();
        List<String> closed = new ArrayList<>();
        runtime.add("only", () -> closed.add("only"));

        runtime.close();
        runtime.close();

        assertEquals(List.of("only"), closed);
    }

    @Test
    void addAndObserveRegisterHealthComponents() {
        BootRuntime runtime = new BootRuntime();
        LocalGameConfigCache cache = runtime.add("configCache",
                new LocalGameConfigCache(new GameConfigValidator(), Clock.systemUTC()));
        GameConfigAutoRecovery recovery = new GameConfigAutoRecovery(callback -> {
        });

        runtime.observe("configRecovery", recovery);

        assertSame(cache, runtime.healthRegistry().configCaches().getFirst());
        assertSame(recovery, runtime.healthRegistry().configRecoveries().getFirst());
    }

    @Test
    void bootActorSchedulesRegistersScheduleView() {
        BootRuntime runtime = new BootRuntime();
        try {
            ActorSystem actors = runtime.add("actors", new ActorSystem(Runnable::run, 64));
            ActorScheduleRegistry schedules = BootActorSchedules.configure(runtime, actors);

            assertSame(schedules, runtime.healthRegistry().actorSchedules().getFirst());
        } finally {
            runtime.close();
        }
    }

    @Test
    void addRegistersDrainableComponents() {
        BootRuntime runtime = new BootRuntime();
        RecordingDrainable drainable = runtime.add("drainable", new RecordingDrainable());

        assertSame(drainable, runtime.healthRegistry().drainableComponents().getFirst());
    }

    private static final class RecordingDrainable implements AutoCloseable, DrainableComponent {
        private final AtomicBoolean draining = new AtomicBoolean();

        @Override
        public void beginDrain() {
            draining.set(true);
        }

        @Override
        public void resumeAccepting() {
            draining.set(false);
        }

        @Override
        public boolean isDraining() {
            return draining.get();
        }

        @Override
        public void close() {
        }
    }
}
