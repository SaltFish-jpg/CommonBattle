package com.commonbattle.actor.agent.migration;

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
 * JDBC Agent 迁移任务字节存储。
 * 表内 version 作为乐观锁版本，compareAndSet/compareAndDelete 通过版本条件保证跨进程 CAS。
 */
public final class JdbcAgentMigrationTaskBytesStore implements AgentMigrationTaskBytesStore {
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final String tableName;

    public JdbcAgentMigrationTaskBytesStore(DataSource dataSource) {
        this(dataSource, "agent_migration_tasks");
    }

    public JdbcAgentMigrationTaskBytesStore(DataSource dataSource, String tableName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tableName = requireTableName(tableName);
    }

    public void initializeSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName
                + " (task_id VARCHAR(255) PRIMARY KEY, version BIGINT NOT NULL, payload BLOB NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize migration task table " + tableName, e);
        }
    }

    @Override
    public void save(String taskId, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        String key = requireTaskId(taskId);
        try (Connection connection = dataSource.getConnection()) {
            if (updateExisting(connection, key, bytes) == 0) {
                insertOrUpdate(connection, key, bytes);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save migration task " + key, e);
        }
    }

    @Override
    public Optional<byte[]> load(String taskId) {
        String key = requireTaskId(taskId);
        String sql = "SELECT payload FROM " + tableName + " WHERE task_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(resultSet.getBytes(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load migration task " + key, e);
        }
    }

    @Override
    public boolean compareAndSet(String taskId, byte[] expected, byte[] updated) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(updated, "updated");
        String key = requireTaskId(taskId);
        try (Connection connection = dataSource.getConnection()) {
            StoredTask current = loadCurrent(connection, key).orElse(null);
            if (current == null || !Arrays.equals(current.payload(), expected)) {
                return false;
            }
            String sql = "UPDATE " + tableName
                    + " SET payload = ?, version = ? WHERE task_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, Arrays.copyOf(updated, updated.length));
                statement.setLong(2, current.version() + 1);
                statement.setString(3, key);
                statement.setLong(4, current.version());
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to compare-and-set migration task " + key, e);
        }
    }

    @Override
    public boolean compareAndDelete(String taskId, byte[] expected) {
        Objects.requireNonNull(expected, "expected");
        String key = requireTaskId(taskId);
        try (Connection connection = dataSource.getConnection()) {
            StoredTask current = loadCurrent(connection, key).orElse(null);
            if (current == null || !Arrays.equals(current.payload(), expected)) {
                return false;
            }
            String sql = "DELETE FROM " + tableName + " WHERE task_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, key);
                statement.setLong(2, current.version());
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to compare-and-delete migration task " + key, e);
        }
    }

    @Override
    public List<byte[]> loadAll() {
        String sql = "SELECT payload FROM " + tableName;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<byte[]> payloads = new ArrayList<>();
            while (resultSet.next()) {
                payloads.add(resultSet.getBytes(1));
            }
            return payloads;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load migration tasks from " + tableName, e);
        }
    }

    private int updateExisting(Connection connection, String taskId, byte[] bytes) throws SQLException {
        String sql = "UPDATE " + tableName + " SET payload = ?, version = version + 1 WHERE task_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, Arrays.copyOf(bytes, bytes.length));
            statement.setString(2, taskId);
            return statement.executeUpdate();
        }
    }

    private void insertOrUpdate(Connection connection, String taskId, byte[] bytes) throws SQLException {
        String sql = "INSERT INTO " + tableName + " (task_id, version, payload) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            statement.setLong(2, 0);
            statement.setBytes(3, Arrays.copyOf(bytes, bytes.length));
            statement.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            updateExisting(connection, taskId, bytes);
        }
    }

    private Optional<StoredTask> loadCurrent(Connection connection, String taskId) throws SQLException {
        String sql = "SELECT version, payload FROM " + tableName + " WHERE task_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredTask(resultSet.getLong(1), resultSet.getBytes(2)));
            }
        }
    }

    private static String requireTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return taskId;
    }

    private static String requireTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        if (!TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid migration task table name: " + tableName);
        }
        return tableName;
    }

    private record StoredTask(long version, byte[] payload) {
    }
}
