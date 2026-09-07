package com.commonbattle.actor.agent.migration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 文件型 Agent 迁移任务字节存储。
 * 适合单节点 WAL、开发环境和轻量恢复验证；多进程生产环境仍应使用 Redis Lua 或 SQL version 字段实现 CAS。
 */
public final class FileAgentMigrationTaskBytesStore implements AgentMigrationTaskBytesStore {
    private static final String SUFFIX = ".task";

    private final Path directory;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public FileAgentMigrationTaskBytesStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create migration task directory " + directory, e);
        }
    }

    @Override
    public void save(String taskId, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        synchronized (lock(taskId)) {
            writeAtomic(path(taskId), bytes);
        }
    }

    @Override
    public Optional<byte[]> load(String taskId) {
        synchronized (lock(taskId)) {
            Path path = path(taskId);
            if (!Files.exists(path)) {
                return Optional.empty();
            }
            return Optional.of(read(path));
        }
    }

    @Override
    public boolean compareAndSet(String taskId, byte[] expected, byte[] updated) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(updated, "updated");
        synchronized (lock(taskId)) {
            Path path = path(taskId);
            if (!Files.exists(path) || !Arrays.equals(read(path), expected)) {
                return false;
            }
            writeAtomic(path, updated);
            return true;
        }
    }

    @Override
    public boolean compareAndDelete(String taskId, byte[] expected) {
        Objects.requireNonNull(expected, "expected");
        synchronized (lock(taskId)) {
            Path path = path(taskId);
            if (!Files.exists(path) || !Arrays.equals(read(path), expected)) {
                return false;
            }
            try {
                Files.delete(path);
                return true;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to delete migration task " + taskId, e);
            }
        }
    }

    @Override
    public List<byte[]> loadAll() {
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .map(FileAgentMigrationTaskBytesStore::read)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list migration task directory " + directory, e);
        }
    }

    private Object lock(String taskId) {
        return locks.computeIfAbsent(requireTaskId(taskId), ignored -> new Object());
    }

    private Path path(String taskId) {
        return directory.resolve(fileName(taskId));
    }

    private static String fileName(String taskId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(requireTaskId(taskId).getBytes(java.nio.charset.StandardCharsets.UTF_8)) + SUFFIX;
    }

    private static String requireTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return taskId;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read migration task file " + path, e);
        }
    }

    private static void writeAtomic(Path path, byte[] bytes) {
        try {
            Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            try {
                Files.write(temp, Arrays.copyOf(bytes, bytes.length));
                moveIntoPlace(temp, path);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write migration task file " + path, e);
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
