package com.commonbattle.game.event;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * JDBC 版本事件 inbox 字节存储。
 * event_key 是 topic、ownerKey、revision 的稳定摘要，主键约束提供跨线程和跨进程幂等写入。
 */
public final class JdbcVersionedEventInboxBytesStore implements VersionedEventInboxBytesStore {
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final String tableName;

    public JdbcVersionedEventInboxBytesStore(DataSource dataSource) {
        this(dataSource, "versioned_event_inbox");
    }

    public JdbcVersionedEventInboxBytesStore(DataSource dataSource, String tableName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tableName = requireTableName(tableName);
    }

    public void initializeSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName
                + " (event_key VARCHAR(64) PRIMARY KEY, payload BLOB NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize event inbox table", e);
        }
    }

    @Override
    public boolean insertIfAbsent(String storageKey, byte[] bytes) {
        String key = requireKey(storageKey);
        Objects.requireNonNull(bytes, "bytes");
        String sql = "INSERT INTO " + tableName + " (event_key, payload) VALUES (?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setBytes(2, copy(bytes));
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (load(key).isPresent()) {
                return false;
            }
            throw new IllegalStateException("Failed to insert event inbox " + key, e);
        }
    }

    @Override
    public void save(String storageKey, byte[] bytes) {
        String key = requireKey(storageKey);
        Objects.requireNonNull(bytes, "bytes");
        try (Connection connection = dataSource.getConnection()) {
            if (update(connection, key, bytes) == 0) {
                insert(connection, key, bytes);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save event inbox " + key, e);
        }
    }

    @Override
    public Optional<byte[]> load(String storageKey) {
        String key = requireKey(storageKey);
        String sql = "SELECT payload FROM " + tableName + " WHERE event_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load event inbox " + key, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        String key = requireKey(storageKey);
        String sql = "DELETE FROM " + tableName + " WHERE event_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete event inbox " + key, e);
        }
    }

    @Override
    public List<byte[]> loadAll() {
        String sql = "SELECT payload FROM " + tableName;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<byte[]> result = new ArrayList<>();
            while (resultSet.next()) {
                result.add(resultSet.getBytes(1));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load event inbox rows from " + tableName, e);
        }
    }

    private int update(Connection connection, String key, byte[] bytes) throws SQLException {
        String sql = "UPDATE " + tableName + " SET payload = ? WHERE event_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, copy(bytes));
            statement.setString(2, key);
            return statement.executeUpdate();
        }
    }

    private void insert(Connection connection, String key, byte[] bytes) throws SQLException {
        String sql = "INSERT INTO " + tableName + " (event_key, payload) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setBytes(2, copy(bytes));
            statement.executeUpdate();
        }
    }

    private static String requireTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        if (!TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid event inbox table name: " + tableName);
        }
        return tableName;
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        return key;
    }

    private static byte[] copy(byte[] bytes) {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
