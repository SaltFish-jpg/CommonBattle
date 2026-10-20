package com.commonbattle.cluster.boot;

import com.commonbattle.observability.RuntimeHealthRegistry;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立进程启动期资源栈。
 * 启动入口把 Actor、网络、订阅、HTTP 等资源注册进来，进程退出时按创建的反向顺序关闭。
 */
final class BootRuntime implements AutoCloseable {
    private final Deque<Resource> resources = new ArrayDeque<>();
    private final RuntimeHealthRegistry healthRegistry = new RuntimeHealthRegistry();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private ShutdownDrain shutdownDrain;
    private Thread shutdownHook;

    <T extends AutoCloseable> T add(String name, T closeable) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(closeable, "closeable");
        synchronized (resources) {
            if (!closed.get()) {
                resources.push(new Resource(name, closeable));
                healthRegistry.register(closeable);
                return closeable;
            }
        }
        closeNow(name, closeable);
        return closeable;
    }

    BootRuntime observe(String name, Object component) {
        Objects.requireNonNull(name, "name");
        healthRegistry.register(component);
        return this;
    }

    RuntimeHealthRegistry healthRegistry() {
        return healthRegistry;
    }

    BootRuntime installShutdownHook() {
        synchronized (resources) {
            if (shutdownHook == null) {
                shutdownHook = new Thread(this::shutdown, "common-battle-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            }
            return this;
        }
    }

    BootRuntime configureShutdownDrain(ShutdownDrain drain) {
        Objects.requireNonNull(drain, "drain");
        synchronized (resources) {
            if (closed.get()) {
                throw new IllegalStateException("boot runtime is already closed");
            }
            if (shutdownDrain != null) {
                throw new IllegalStateException("shutdown drain is already configured");
            }
            shutdownDrain = drain;
            return this;
        }
    }

    void shutdown() {
        if (closed.get() || !shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        ShutdownDrain drain;
        synchronized (resources) {
            drain = shutdownDrain;
        }
        if (drain != null) {
            try {
                drain.awaitDrained();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new IllegalStateException("Interrupted while draining boot runtime", e);
            } catch (Exception e) {
                failure = new IllegalStateException("Failed to drain boot runtime", e);
            }
        }
        try {
            close();
        } catch (RuntimeException closeError) {
            if (failure == null) {
                failure = closeError;
            } else {
                failure.addSuppressed(closeError);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        while (true) {
            Resource resource;
            synchronized (resources) {
                resource = resources.poll();
            }
            if (resource == null) {
                break;
            }
            try {
                resource.closeable().close();
            } catch (Throwable e) {
                RuntimeException wrapped = new IllegalStateException("Failed to close boot resource: " + resource.name(), e);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    void closeSuppressing(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        try {
            close();
        } catch (RuntimeException closeError) {
            cause.addSuppressed(closeError);
        }
    }

    private static void closeNow(String name, AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to close boot resource after runtime closed: " + name, e);
        }
    }

    private record Resource(String name, AutoCloseable closeable) {
    }

    @FunctionalInterface
    interface ShutdownDrain {
        void awaitDrained() throws Exception;
    }
}
