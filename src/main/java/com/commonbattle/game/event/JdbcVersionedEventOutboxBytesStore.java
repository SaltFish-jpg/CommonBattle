package com.commonbattle.game.event;

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
 * JDBC 版本事件 outbox 字节存储。
 * sequence 表只负责分配单调 outboxId，pending 表保存事件 payload；ID 空洞允许存在，不能复用。
 */
public final class JdbcVersionedEventOutboxBytesStore implements VersionedEventOutboxBytesStore {
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final String SEQUENCE_NAME = "default";

    private final DataSource dataSource;
    private final String tableName;
    private final String sequenceTableName;

    public JdbcVersionedEventOutboxBytesStore(DataSource dataSource) {
        this(dataSource, "versioned_event_outbox", "versioned_event_outbox_sequence");
    }

    public JdbcVersionedEventOutboxBytesStore(DataSource dataSource, String tableName, String sequenceTableName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tableName = requireTableName(tableName);
        this.sequenceTableName = requireTableName(sequenceTableName);
    }

    public void initializeSchema() {
        String outboxSql = "CREATE TABLE IF NOT EXISTS " + tableName
                + " (outbox_id BIGINT PRIMARY KEY, payload BLOB NOT NULL)";
        String sequenceSql = "CREATE TABLE IF NOT EXISTS " + sequenceTableName
                + " (sequence_name VARCHAR(64) PRIMARY KEY, next_value BIGINT NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(outboxSql);
            statement.execute(sequenceSql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize event outbox tables", e);
        }
    }

    @Override
    public long nextId() {
        while (true) {
            try (Connection connection = dataSource.getConnection()) {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    long next = loadNextValue(connection).orElse(1L);
                    if (updateNextValue(connection, next, next + 1) == 1 || insertInitialSequence(connection)) {
                        connection.commit();
                        connection.setAutoCommit(autoCommit);
                        return next;
                    }
                    connection.rollback();
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException | RuntimeException e) {
                    rollbackQuietly(connection);
                    connection.setAutoCommit(autoCommit);
                    throw e;
                }
            } catch (SQLIntegrityConstraintViolationException ignored) {
                // 另一个进程刚创建 sequence 行，重试后走 update。
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to allocate event outbox id", e);
            }
        }
    }

    @Override
    public void save(long outboxId, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        String sql = "INSERT INTO " + tableName + " (outbox_id, payload) VALUES (?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, requireOutboxId(outboxId));
            statement.setBytes(2, copy(bytes));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save event outbox " + outboxId, e);
        }
    }

    @Override
    public Optional<byte[]> load(long outboxId) {
        String sql = "SELECT payload FROM " + tableName + " WHERE outbox_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, requireOutboxId(outboxId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(resultSet.getBytes(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load event outbox " + outboxId, e);
        }
    }

    @Override
    public void delete(long outboxId) {
        String sql = "DELETE FROM " + tableName + " WHERE outbox_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, requireOutboxId(outboxId));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete event outbox " + outboxId, e);
        }
    }

    @Override
    public List<byte[]> loadAll() {
        String sql = "SELECT payload FROM " + tableName + " ORDER BY outbox_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<byte[]> payloads = new ArrayList<>();
            while (resultSet.next()) {
                payloads.add(resultSet.getBytes(1));
            }
            return payloads;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load event outbox rows from " + tableName, e);
        }
    }

    private Optional<Long> loadNextValue(Connection connection) throws SQLException {
        String sql = "SELECT next_value FROM " + sequenceTableName + " WHERE sequence_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SEQUENCE_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(resultSet.getLong(1));
            }
        }
    }

    private int updateNextValue(Connection connection, long expected, long updated) throws SQLException {
        String sql = "UPDATE " + sequenceTableName
                + " SET next_value = ? WHERE sequence_name = ? AND next_value = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, updated);
            statement.setString(2, SEQUENCE_NAME);
            statement.setLong(3, expected);
            return statement.executeUpdate();
        }
    }

    private boolean insertInitialSequence(Connection connection) throws SQLException {
        String sql = "INSERT INTO " + sequenceTableName + " (sequence_name, next_value) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SEQUENCE_NAME);
            statement.setLong(2, 2);
            return statement.executeUpdate() == 1;
        }
    }

    private static long requireOutboxId(long outboxId) {
        if (outboxId <= 0) {
            throw new IllegalArgumentException("outboxId must be positive");
        }
        return outboxId;
    }

    private static String requireTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        if (!TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid event outbox table name: " + tableName);
        }
        return tableName;
    }

    private static byte[] copy(byte[] bytes) {
        return Arrays.copyOf(bytes, bytes.length);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }
}
