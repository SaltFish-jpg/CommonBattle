package com.commonbattle.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 基于 JDBC 乐观版本号的原子字节存储。
 * 适用于多 Game 进程共享的玩家提交记录；CAS 不依赖 JVM 内锁，可跨进程检测并发写入。
 */
public final class JdbcAtomicBytesStore implements AtomicBytesStore {
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final String tableName;

    public JdbcAtomicBytesStore(DataSource dataSource) {
        this(dataSource, "atomic_bytes_store");
    }

    public JdbcAtomicBytesStore(DataSource dataSource, String tableName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tableName = requireTableName(tableName);
    }

    public void initializeSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName
                + " (record_key VARCHAR(512) PRIMARY KEY, payload BLOB NOT NULL, record_version BIGINT NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize atomic bytes table " + tableName, e);
        }
    }

    @Override
    public Optional<byte[]> load(String key) {
        String sql = "SELECT payload FROM " + tableName + " WHERE record_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireKey(key));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(copy(resultSet.getBytes(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load atomic bytes key " + key, e);
        }
    }

    @Override
    public void put(String key, byte[] bytes) {
        key = requireKey(key);
        bytes = copy(bytes);
        try (Connection connection = dataSource.getConnection()) {
            if (updateAnyVersion(connection, key, bytes) == 0) {
                try {
                    insert(connection, key, bytes);
                } catch (SQLException race) {
                    if (!constraintViolation(race) || updateAnyVersion(connection, key, bytes) == 0) {
                        throw race;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to put atomic bytes key " + key, e);
        }
    }

    @Override
    public boolean putIfAbsent(String key, byte[] bytes) {
        try (Connection connection = dataSource.getConnection()) {
            insert(connection, requireKey(key), copy(bytes));
            return true;
        } catch (SQLException e) {
            if (constraintViolation(e)) {
                return false;
            }
            throw new IllegalStateException("Failed to insert atomic bytes key " + key, e);
        }
    }

    @Override
    public boolean compareAndSet(String key, byte[] expected, byte[] updated) {
        return compareAndChange(requireKey(key), copy(expected), copy(updated), false);
    }

    @Override
    public boolean compareAndDelete(String key, byte[] expected) {
        return compareAndChange(requireKey(key), copy(expected), null, true);
    }

    @Override
    public List<Entry> scanPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        String sql = "SELECT record_key, payload FROM " + tableName
                + " WHERE record_key LIKE ? ESCAPE '!' ORDER BY record_key";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, escapeLike(prefix) + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Entry> entries = new ArrayList<>();
                while (resultSet.next()) {
                    entries.add(new Entry(resultSet.getString(1), copy(resultSet.getBytes(2))));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to scan atomic bytes prefix " + prefix, e);
        }
    }

    private boolean compareAndChange(String key, byte[] expected, byte[] updated, boolean delete) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                StoredRow current = loadRow(connection, key).orElse(null);
                if (current == null || !Arrays.equals(current.payload(), expected)) {
                    connection.rollback();
                    return false;
                }
                int changed = delete
                        ? deleteVersion(connection, key, current.version())
                        : updateVersion(connection, key, current.version(), updated);
                if (changed != 1) {
                    connection.rollback();
                    return false;
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to compare and change atomic bytes key " + key, e);
        }
    }

    private Optional<StoredRow> loadRow(Connection connection, String key) throws SQLException {
        String sql = "SELECT payload, record_version FROM " + tableName + " WHERE record_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredRow(copy(resultSet.getBytes(1)), resultSet.getLong(2)));
            }
        }
    }

    private int updateAnyVersion(Connection connection, String key, byte[] bytes) throws SQLException {
        String sql = "UPDATE " + tableName
                + " SET payload = ?, record_version = record_version + 1 WHERE record_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, bytes);
            statement.setString(2, key);
            return statement.executeUpdate();
        }
    }

    private int updateVersion(Connection connection, String key, long version, byte[] bytes) throws SQLException {
        String sql = "UPDATE " + tableName
                + " SET payload = ?, record_version = record_version + 1"
                + " WHERE record_key = ? AND record_version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, bytes);
            statement.setString(2, key);
            statement.setLong(3, version);
            return statement.executeUpdate();
        }
    }

    private int deleteVersion(Connection connection, String key, long version) throws SQLException {
        String sql = "DELETE FROM " + tableName + " WHERE record_key = ? AND record_version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setLong(2, version);
            return statement.executeUpdate();
        }
    }

    private void insert(Connection connection, String key, byte[] bytes) throws SQLException {
        String sql = "INSERT INTO " + tableName + " (record_key, payload, record_version) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setBytes(2, bytes);
            statement.setLong(3, 1);
            statement.executeUpdate();
        }
    }

    private static boolean constraintViolation(SQLException error) {
        if (error instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String sqlState = error.getSQLState();
        return sqlState != null && sqlState.startsWith("23");
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String requireTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        if (!TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid atomic bytes table name: " + tableName);
        }
        return tableName;
    }

    private static String requireKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (key.length() > 512) {
            throw new IllegalArgumentException("key is longer than 512 characters");
        }
        return key;
    }

    private static byte[] copy(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return Arrays.copyOf(bytes, bytes.length);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
        }
    }

    private record StoredRow(byte[] payload, long version) {
    }
}
