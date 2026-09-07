package com.commonbattle.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于本地文件的原子字节存储。
 * 适合开发、本地压测和单机部署；多进程共享生产环境应替换为 Redis 或 DB 实现。
 */
public final class FileAtomicBytesStore implements AtomicBytesStore {
    private static final String SUFFIX = ".bin";

    private final Path directory;

    public FileAtomicBytesStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create bytes store directory: " + directory, e);
        }
    }

    @Override
    public synchronized Optional<byte[]> load(String key) {
        Objects.requireNonNull(key, "key");
        Path path = path(key);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load bytes for key: " + key, e);
        }
    }

    @Override
    public synchronized void put(String key, byte[] bytes) {
        write(key, bytes);
    }

    @Override
    public synchronized boolean putIfAbsent(String key, byte[] bytes) {
        Objects.requireNonNull(key, "key");
        if (Files.exists(path(key))) {
            return false;
        }
        write(key, bytes);
        return true;
    }

    @Override
    public synchronized boolean compareAndSet(String key, byte[] expected, byte[] updated) {
        Optional<byte[]> current = load(key);
        if (current.isEmpty() || !Arrays.equals(current.orElseThrow(), expected)) {
            return false;
        }
        write(key, updated);
        return true;
    }

    @Override
    public synchronized boolean compareAndDelete(String key, byte[] expected) {
        Optional<byte[]> current = load(key);
        if (current.isEmpty() || !Arrays.equals(current.orElseThrow(), expected)) {
            return false;
        }
        try {
            Files.deleteIfExists(path(key));
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete bytes for key: " + key, e);
        }
    }

    @Override
    public synchronized List<Entry> scanPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .map(this::entry)
                    .flatMap(Optional::stream)
                    .filter(entry -> entry.key().startsWith(prefix))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan bytes store directory: " + directory, e);
        }
    }

    private Optional<Entry> entry(Path path) {
        String fileName = path.getFileName().toString();
        String encoded = fileName.substring(0, fileName.length() - SUFFIX.length());
        try {
            String key = new String(Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
            return Optional.of(new Entry(key, Files.readAllBytes(path)));
        } catch (RuntimeException | IOException e) {
            return Optional.empty();
        }
    }

    private void write(String key, byte[] bytes) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(bytes, "bytes");
        Path target = path(key);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallback) {
                throw new IllegalStateException("Failed to write bytes for key: " + key, fallback);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write bytes for key: " + key, e);
        }
    }

    private Path path(String key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return directory.resolve(encoded + SUFFIX);
    }
}
