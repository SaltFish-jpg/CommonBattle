package com.commonbattle.cluster.event;

import com.commonbattle.cluster.protocol.EncodedPayload;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.event.VersionedEvent;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JDBC 中心事件日志。
 * 事件追加与历史裁剪在同一事务中提交，sequence 仅分配稳定排序号，允许回滚产生空洞。
 */
public final class JdbcClusterEventJournal implements ClusterEventJournal {
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final PayloadCodecRegistry codecs;
    private final String namespace;
    private final String tableName;
    private final String sequenceTableName;
    private final String cursorTableName;

    public JdbcClusterEventJournal(
            DataSource dataSource,
            PayloadCodecRegistry codecs,
            String namespace
    ) {
        this(dataSource, codecs, namespace, "cluster_event_journal", "cluster_event_journal_sequence",
                "cluster_event_journal_cursor");
    }

    public JdbcClusterEventJournal(
            DataSource dataSource,
            PayloadCodecRegistry codecs,
            String namespace,
            String tableName,
            String sequenceTableName
    ) {
        this(dataSource, codecs, namespace, tableName, sequenceTableName, "cluster_event_journal_cursor");
    }

    public JdbcClusterEventJournal(
            DataSource dataSource,
            PayloadCodecRegistry codecs,
            String namespace,
            String tableName,
            String sequenceTableName,
            String cursorTableName
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codecs = Objects.requireNonNull(codecs, "codecs");
        this.namespace = requireText(namespace, "namespace");
        this.tableName = requireTableName(tableName);
        this.sequenceTableName = requireTableName(sequenceTableName);
        this.cursorTableName = requireTableName(cursorTableName);
    }

    public void initializeSchema() {
        String journalSql = "CREATE TABLE IF NOT EXISTS " + tableName
                + " (event_key VARCHAR(64) PRIMARY KEY, journal_namespace VARCHAR(128) NOT NULL,"
                + " topic VARCHAR(255) NOT NULL, journal_order BIGINT NOT NULL, payload BLOB NOT NULL)";
        String sequenceSql = "CREATE TABLE IF NOT EXISTS " + sequenceTableName
                + " (sequence_name VARCHAR(128) PRIMARY KEY, next_value BIGINT NOT NULL)";
        String cursorSql = "CREATE TABLE IF NOT EXISTS " + cursorTableName
                + " (stream_key VARCHAR(64) PRIMARY KEY, journal_namespace VARCHAR(128) NOT NULL,"
                + " topic VARCHAR(255) NOT NULL, owner_key VARCHAR(512) NOT NULL, max_revision BIGINT NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(journalSql);
            statement.execute(sequenceSql);
            statement.execute(cursorSql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize cluster event journal tables", e);
        }
    }

    @Override
    public synchronized ClusterEventJournalAppendResult appendAndTrim(VersionedEvent event, int historyLimit) {
        Objects.requireNonNull(event, "event");
        if (historyLimit <= 0) {
            throw new IllegalArgumentException("historyLimit must be positive");
        }
        ClusterEventJournalKey key = ClusterEventJournalKey.of(event);
        String storageKey = storageKey(namespace, key);
        Optional<VersionedEvent> existing = load(storageKey);
        if (existing.isPresent()) {
            requireSameEvent(key, existing.orElseThrow(), event);
            return new ClusterEventJournalAppendResult(ClusterEventJournalAppendStatus.DUPLICATE, 0);
        }
        String streamKey = streamKey(namespace, event.topic(), event.ownerKey());
        if (loadHighWaterMark(streamKey).orElse(0L) >= event.revision()) {
            return new ClusterEventJournalAppendResult(ClusterEventJournalAppendStatus.DUPLICATE, 0);
        }
        long order = nextOrder();
        EncodedPayload payload = codecs.encode(new EventPublishRequest(event));
        byte[] bytes = StoredEventPayload.encode(payload);
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                insert(connection, storageKey, event.topic(), order, bytes);
                int trimmed = trim(connection, event.topic(), historyLimit);
                saveHighWaterMark(connection, streamKey, event);
                connection.commit();
                connection.setAutoCommit(autoCommit);
                return new ClusterEventJournalAppendResult(ClusterEventJournalAppendStatus.APPENDED, trimmed);
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection);
                connection.setAutoCommit(autoCommit);
                throw e;
            }
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return duplicateResult(storageKey, key, event, duplicate);
        } catch (SQLException e) {
            Optional<VersionedEvent> raced = load(storageKey);
            if (raced.isPresent()) {
                requireSameEvent(key, raced.orElseThrow(), event);
                return new ClusterEventJournalAppendResult(ClusterEventJournalAppendStatus.DUPLICATE, 0);
            }
            throw new IllegalStateException("Failed to append cluster event " + key, e);
        }
    }

    @Override
    public List<VersionedEvent> retained(String topic) {
        Objects.requireNonNull(topic, "topic");
        String sql = "SELECT payload FROM " + tableName
                + " WHERE journal_namespace = ? AND topic = ? ORDER BY journal_order";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, topic);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<VersionedEvent> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(decode(resultSet.getBytes(1)));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read cluster event journal topic " + topic, e);
        }
    }

    @Override
    public Set<String> topics() {
        String sql = "SELECT DISTINCT topic FROM " + tableName + " WHERE journal_namespace = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> result = new HashSet<>();
                while (resultSet.next()) {
                    result.add(resultSet.getString(1));
                }
                return Set.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list cluster event journal topics", e);
        }
    }

    @Override
    public long highWaterMark(String topic, String ownerKey) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(ownerKey, "ownerKey");
        return loadHighWaterMark(streamKey(namespace, topic, ownerKey)).orElse(0L);
    }

    private long nextOrder() {
        while (true) {
            try (Connection connection = dataSource.getConnection()) {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    long next = loadNextOrder(connection).orElse(1L);
                    if (updateNextOrder(connection, next, next + 1) == 1 || insertInitialSequence(connection)) {
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
                // 另一中心节点刚初始化同一 namespace 的序列，重试 CAS 分配。
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to allocate cluster event journal order", e);
            }
        }
    }

    private Optional<Long> loadNextOrder(Connection connection) throws SQLException {
        String sql = "SELECT next_value FROM " + sequenceTableName + " WHERE sequence_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getLong(1)) : Optional.empty();
            }
        }
    }

    private int updateNextOrder(Connection connection, long expected, long updated) throws SQLException {
        String sql = "UPDATE " + sequenceTableName
                + " SET next_value = ? WHERE sequence_name = ? AND next_value = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, updated);
            statement.setString(2, namespace);
            statement.setLong(3, expected);
            return statement.executeUpdate();
        }
    }

    private boolean insertInitialSequence(Connection connection) throws SQLException {
        String sql = "INSERT INTO " + sequenceTableName + " (sequence_name, next_value) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setLong(2, 2L);
            return statement.executeUpdate() == 1;
        }
    }

    private void insert(Connection connection, String key, String topic, long order, byte[] payload) throws SQLException {
        String sql = "INSERT INTO " + tableName
                + " (event_key, journal_namespace, topic, journal_order, payload) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, namespace);
            statement.setString(3, topic);
            statement.setLong(4, order);
            statement.setBytes(5, payload);
            statement.executeUpdate();
        }
    }

    private int trim(Connection connection, String topic, int historyLimit) throws SQLException {
        String select = "SELECT event_key FROM " + tableName
                + " WHERE journal_namespace = ? AND topic = ? ORDER BY journal_order";
        List<String> keys = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setString(1, namespace);
            statement.setString(2, topic);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    keys.add(resultSet.getString(1));
                }
            }
        }
        int trimmed = Math.max(0, keys.size() - historyLimit);
        if (trimmed == 0) {
            return 0;
        }
        String delete = "DELETE FROM " + tableName + " WHERE event_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(delete)) {
            for (int index = 0; index < trimmed; index++) {
                statement.setString(1, keys.get(index));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return trimmed;
    }

    private Optional<VersionedEvent> load(String storageKey) {
        String sql = "SELECT payload FROM " + tableName + " WHERE event_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, storageKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(decode(resultSet.getBytes(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load cluster event journal entry " + storageKey, e);
        }
    }

    private Optional<Long> loadHighWaterMark(String streamKey) {
        String sql = "SELECT max_revision FROM " + cursorTableName + " WHERE stream_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, streamKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load cluster event journal cursor " + streamKey, e);
        }
    }

    private void saveHighWaterMark(Connection connection, String streamKey, VersionedEvent event) throws SQLException {
        String update = "UPDATE " + cursorTableName
                + " SET max_revision = ? WHERE stream_key = ? AND max_revision < ?";
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setLong(1, event.revision());
            statement.setString(2, streamKey);
            statement.setLong(3, event.revision());
            if (statement.executeUpdate() == 1) {
                return;
            }
        }
        String insert = "INSERT INTO " + cursorTableName
                + " (stream_key, journal_namespace, topic, owner_key, max_revision) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, streamKey);
            statement.setString(2, namespace);
            statement.setString(3, event.topic());
            statement.setString(4, event.ownerKey());
            statement.setLong(5, event.revision());
            statement.executeUpdate();
        }
    }

    private VersionedEvent decode(byte[] bytes) {
        StoredEventPayload payload = StoredEventPayload.decode(bytes);
        Object decoded = codecs.decode(payload.codecName(), payload.typeName(), payload.bytes());
        if (!(decoded instanceof EventPublishRequest request) || request.event() == null) {
            throw new IllegalStateException("Cluster event journal payload is not an event publish request");
        }
        return request.event();
    }

    private ClusterEventJournalAppendResult duplicateResult(
            String storageKey,
            ClusterEventJournalKey key,
            VersionedEvent event,
            SQLException cause
    ) {
        VersionedEvent existing = load(storageKey)
                .orElseThrow(() -> new IllegalStateException("Duplicate event row disappeared for " + key, cause));
        requireSameEvent(key, existing, event);
        return new ClusterEventJournalAppendResult(ClusterEventJournalAppendStatus.DUPLICATE, 0);
    }

    private static String storageKey(String namespace, ClusterEventJournalKey key) {
        String identity = namespace.length() + ":" + namespace
                + key.topic().length() + ":" + key.topic()
                + key.ownerKey().length() + ":" + key.ownerKey()
                + ":" + key.revision();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String streamKey(String namespace, String topic, String ownerKey) {
        String identity = namespace.length() + ":" + namespace
                + topic.length() + ":" + topic
                + ownerKey.length() + ":" + ownerKey;
        return sha256(identity);
    }

    private static String sha256(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void requireSameEvent(
            ClusterEventJournalKey key,
            VersionedEvent existing,
            VersionedEvent candidate
    ) {
        if (!existing.equals(candidate)) {
            throw new IllegalStateException("Event journal key collision for " + key);
        }
    }

    private static String requireTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        if (!TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid cluster event journal table name: " + tableName);
        }
        return tableName;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }
}
