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
                shutdownHook = new Thread(this::close, "common-battle-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            }
            return this;
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
            } catch (Exception e) {
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

    void closeSuppressing(Exception cause) {
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
        } catch (Exception e) {
            throw new IllegalStateException("Failed to close boot resource after runtime closed: " + name, e);
        }
    }

    private record Resource(String name, AutoCloseable closeable) {
    }
}
