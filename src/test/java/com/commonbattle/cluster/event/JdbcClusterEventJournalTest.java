package com.commonbattle.cluster.event;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcClusterEventJournalTest {
    @Test
    void initializesSchemaAndPersistsIdempotentTrimmedHistory() {
        FakeJournalDatabase database = new FakeJournalDatabase();
        JdbcClusterEventJournal journal = journal(database);
        journal.initializeSchema();
        ProfileChangedEvent first = event(1, "avatar_1");
        ProfileChangedEvent second = event(2, "avatar_2");

        assertEquals(ClusterEventJournalAppendStatus.APPENDED, journal.appendAndTrim(first, 1).status());
        assertEquals(ClusterEventJournalAppendStatus.DUPLICATE, journal.appendAndTrim(first, 1).status());
        assertEquals(1, journal.appendAndTrim(second, 1).trimmedEvents());

        assertEquals(3, database.executedStatements.size());
        assertEquals(Set.of(ProfileChangedEvent.TOPIC), journal.topics());
        assertEquals(List.of(second), journal.retained(ProfileChangedEvent.TOPIC));
        assertEquals(ClusterEventJournalAppendStatus.DUPLICATE, journal.appendAndTrim(first, 1).status());
        assertEquals(List.of(second), journal.retained(ProfileChangedEvent.TOPIC));
    }

    @Test
    void sharedNamespaceSurvivesJournalReconstruction() {
        FakeJournalDatabase database = new FakeJournalDatabase();
        journal(database).appendAndTrim(event(1, "avatar_1"), 10);

        JdbcClusterEventJournal restored = journal(database);

        assertEquals(List.of(event(1, "avatar_1")), restored.retained(ProfileChangedEvent.TOPIC));
    }

    @Test
    void rejectsUnsafeTableNames() {
        FakeJournalDatabase database = new FakeJournalDatabase();
        PayloadCodecRegistry codecs = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());

        assertThrows(IllegalArgumentException.class,
                () -> new JdbcClusterEventJournal(database.dataSource(), codecs, "r1", "journal;drop", "seq"));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcClusterEventJournal(database.dataSource(), codecs, "r1", "journal", "seq;drop"));
    }

    private static JdbcClusterEventJournal journal(FakeJournalDatabase database) {
        return new JdbcClusterEventJournal(
                database.dataSource(),
                ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults()),
                "r1"
        );
    }

    private static ProfileChangedEvent event(long revision, String avatar) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        20,
                        new AppearanceSummary(avatar, "frame", "costume"),
                        new AllianceBrief(100, "alliance", "badge"),
                        new FriendBrief(3, 1),
                        revision,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
    }

    private static final class FakeJournalDatabase {
        private final Map<String, Row> rows = new LinkedHashMap<>();
        private final Map<String, Long> sequences = new LinkedHashMap<>();
        private final Map<String, Long> cursors = new LinkedHashMap<>();
        private final List<String> executedStatements = new ArrayList<>();

        private DataSource dataSource() {
            return proxy(DataSource.class, (ignored, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    return connection();
                }
                return defaultValue(method.getReturnType());
            });
        }

        private Connection connection() {
            class State {
                boolean autoCommit = true;
            }
            State state = new State();
            return proxy(Connection.class, (ignored, method, args) -> switch (method.getName()) {
                case "createStatement" -> statement();
                case "prepareStatement" -> preparedStatement((String) args[0]);
                case "getAutoCommit" -> state.autoCommit;
                case "setAutoCommit" -> {
                    state.autoCommit = (Boolean) args[0];
                    yield null;
                }
                case "commit", "rollback", "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private Statement statement() {
            return proxy(Statement.class, (ignored, method, args) -> {
                if (method.getName().equals("execute")) {
                    executedStatements.add((String) args[0]);
                    return false;
                }
                return defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement preparedStatement(String sql) {
            Map<Integer, Object> parameters = new LinkedHashMap<>();
            List<Map<Integer, Object>> batches = new ArrayList<>();
            return proxy(PreparedStatement.class, (ignored, method, args) -> switch (method.getName()) {
                case "setString", "setLong", "setBytes" -> {
                    parameters.put((Integer) args[0], copy(args[1]));
                    yield null;
                }
                case "executeUpdate" -> executeUpdate(sql, parameters);
                case "executeQuery" -> resultSet(executeQuery(sql, parameters));
                case "addBatch" -> {
                    batches.add(copyParameters(parameters));
                    yield null;
                }
                case "executeBatch" -> batches.stream().mapToInt(batch -> {
                    try {
                        return executeUpdate(sql, batch);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toArray();
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private int executeUpdate(String sql, Map<Integer, Object> parameters) throws Exception {
            if (sql.startsWith("UPDATE cluster_event_journal_sequence SET next_value")) {
                String namespace = (String) parameters.get(2);
                Long expected = (Long) parameters.get(3);
                if (!expected.equals(sequences.get(namespace))) {
                    return 0;
                }
                sequences.put(namespace, (Long) parameters.get(1));
                return 1;
            }
            if (sql.startsWith("INSERT INTO cluster_event_journal_sequence")) {
                String namespace = (String) parameters.get(1);
                if (sequences.containsKey(namespace)) {
                    throw new SQLIntegrityConstraintViolationException("duplicate sequence");
                }
                sequences.put(namespace, (Long) parameters.get(2));
                return 1;
            }
            if (sql.startsWith("INSERT INTO cluster_event_journal ")) {
                String key = (String) parameters.get(1);
                if (rows.containsKey(key)) {
                    throw new SQLIntegrityConstraintViolationException("duplicate event");
                }
                rows.put(key, new Row(
                        (String) parameters.get(2),
                        (String) parameters.get(3),
                        (Long) parameters.get(4),
                        bytes(parameters.get(5))
                ));
                return 1;
            }
            if (sql.startsWith("UPDATE cluster_event_journal_cursor SET max_revision")) {
                String streamKey = (String) parameters.get(2);
                Long current = cursors.get(streamKey);
                if (current == null || current >= (Long) parameters.get(3)) {
                    return 0;
                }
                cursors.put(streamKey, (Long) parameters.get(1));
                return 1;
            }
            if (sql.startsWith("INSERT INTO cluster_event_journal_cursor")) {
                String streamKey = (String) parameters.get(1);
                if (cursors.containsKey(streamKey)) {
                    throw new SQLIntegrityConstraintViolationException("duplicate cursor");
                }
                cursors.put(streamKey, (Long) parameters.get(5));
                return 1;
            }
            if (sql.startsWith("DELETE FROM cluster_event_journal WHERE event_key")) {
                return rows.remove((String) parameters.get(1)) == null ? 0 : 1;
            }
            throw new IllegalStateException("Unexpected SQL update: " + sql);
        }

        private List<Object[]> executeQuery(String sql, Map<Integer, Object> parameters) {
            if (sql.startsWith("SELECT next_value FROM cluster_event_journal_sequence")) {
                Long next = sequences.get((String) parameters.get(1));
                return next == null ? List.of() : rows(new Object[]{next});
            }
            if (sql.equals("SELECT payload FROM cluster_event_journal WHERE event_key = ?")) {
                Row row = rows.get((String) parameters.get(1));
                return row == null ? List.of() : rows(new Object[]{bytes(row.payload())});
            }
            if (sql.startsWith("SELECT max_revision FROM cluster_event_journal_cursor")) {
                Long revision = cursors.get((String) parameters.get(1));
                return revision == null ? List.of() : rows(new Object[]{revision});
            }
            if (sql.startsWith("SELECT event_key FROM cluster_event_journal ")) {
                String namespace = (String) parameters.get(1);
                String topic = (String) parameters.get(2);
                return ordered(namespace, topic).stream().map(entry -> new Object[]{entry.getKey()}).toList();
            }
            if (sql.startsWith("SELECT payload FROM cluster_event_journal WHERE journal_namespace")) {
                String namespace = (String) parameters.get(1);
                String topic = (String) parameters.get(2);
                return ordered(namespace, topic).stream()
                        .map(entry -> new Object[]{bytes(entry.getValue().payload())})
                        .toList();
            }
            if (sql.startsWith("SELECT DISTINCT topic FROM cluster_event_journal")) {
                String namespace = (String) parameters.get(1);
                return rows.values().stream()
                        .filter(row -> row.namespace().equals(namespace))
                        .map(Row::topic)
                        .distinct()
                        .map(topic -> new Object[]{topic})
                        .toList();
            }
            throw new IllegalStateException("Unexpected SQL query: " + sql);
        }

        private List<Map.Entry<String, Row>> ordered(String namespace, String topic) {
            return rows.entrySet().stream()
                    .filter(entry -> entry.getValue().namespace().equals(namespace))
                    .filter(entry -> entry.getValue().topic().equals(topic))
                    .sorted(Comparator.comparingLong(entry -> entry.getValue().order()))
                    .toList();
        }

        private static ResultSet resultSet(List<Object[]> rows) {
            class Cursor {
                int index = -1;
            }
            Cursor cursor = new Cursor();
            return proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
                case "next" -> ++cursor.index < rows.size();
                case "getString" -> rows.get(cursor.index)[(Integer) args[0] - 1];
                case "getLong" -> rows.get(cursor.index)[(Integer) args[0] - 1];
                case "getBytes" -> bytes(rows.get(cursor.index)[(Integer) args[0] - 1]);
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private static List<Object[]> rows(Object[] row) {
            List<Object[]> result = new ArrayList<>();
            result.add(row);
            return result;
        }

        private static Map<Integer, Object> copyParameters(Map<Integer, Object> source) {
            Map<Integer, Object> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(key, copy(value)));
            return result;
        }

        private static Object copy(Object value) {
            return value instanceof byte[] valueBytes ? bytes(valueBytes) : value;
        }

        private static byte[] bytes(Object value) {
            byte[] valueBytes = (byte[]) value;
            return Arrays.copyOf(valueBytes, valueBytes.length);
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            return null;
        }

        private record Row(String namespace, String topic, long order, byte[] payload) {
        }
    }
}
