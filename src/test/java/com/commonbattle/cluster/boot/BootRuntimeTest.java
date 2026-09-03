package com.commonbattle.cluster.boot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
