package com.commonbattle.game.shop;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 基于数据库事务的全局商店库存仓储。
 * 条件扣库存和 reservation 唯一键写入在同一事务内完成，适合多 Center 实例竞争与崩溃恢复。
 */
public final class JdbcShopStockRepository implements ShopStockReservationRepository {
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final int MAX_TRANSACTION_ATTEMPTS = 8;

    private final DataSource dataSource;
    private final String stockTable;
    private final String reservationTable;
    private final Clock clock;
    private final Duration reservationTtl;

    public JdbcShopStockRepository(
            DataSource dataSource,
            String stockTable,
            String reservationTable,
            Clock clock,
            Duration reservationTtl
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.stockTable = requireTableName(stockTable);
        this.reservationTable = requireTableName(reservationTable);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reservationTtl = Objects.requireNonNull(reservationTtl, "reservationTtl");
        if (reservationTtl.isNegative()) {
            throw new IllegalArgumentException("reservationTtl must not be negative");
        }
    }

    public void initializeSchema() {
        String stockSql = "CREATE TABLE IF NOT EXISTS " + stockTable
                + " (sku VARCHAR(255) PRIMARY KEY, remaining INT NOT NULL)";
        String reservationSql = "CREATE TABLE IF NOT EXISTS " + reservationTable
                + " (reservation_id VARCHAR(255) PRIMARY KEY, sku VARCHAR(255) NOT NULL,"
                + " reserved_count INT NOT NULL, expires_at BIGINT NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(stockSql);
            statement.execute(reservationSql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize shop stock tables", e);
        }
    }

    public void set(String sku, int count) {
        requireSku(sku);
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                boolean autoCommit = begin(connection);
                try {
                    if (updateRemaining(connection, sku, count) == 0) {
                        insertStock(connection, sku, count);
                    }
                    connection.commit();
                    restoreAutoCommit(connection, autoCommit);
                    return;
                } catch (SQLException error) {
                    rollbackQuietly(connection);
                    restoreAutoCommit(connection, autoCommit);
                    if (!retryable(error) || attempt + 1 == MAX_TRANSACTION_ATTEMPTS) {
                        throw error;
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to set shop stock for " + sku, e);
            }
        }
    }

    public void seed(ShopCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        catalog.definitions().stream()
                .filter(ShopItemDefinition::limitedStock)
                .forEach(definition -> set(definition.sku(), definition.globalStock()));
    }

    @Override
    public boolean reserve(String sku, int count) {
        requireSku(sku);
        validateCount(count);
        String sql = "UPDATE " + stockTable + " SET remaining = remaining - ?"
                + " WHERE sku = ? AND remaining >= ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, count);
            statement.setString(2, sku);
            statement.setInt(3, count);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reserve shop stock for " + sku, e);
        }
    }

    @Override
    public boolean reserve(String reservationId, String sku, int count) {
        requireSku(sku);
        validateCount(count);
        if (reservationId == null || reservationId.isBlank()) {
            return reserve(sku, count);
        }
        requireReservationId(reservationId);
        for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                boolean autoCommit = begin(connection);
                try {
                    Reservation existing = findReservation(connection, reservationId);
                    if (existing != null && !existing.expiredAt(clock.instant())) {
                        connection.commit();
                        restoreAutoCommit(connection, autoCommit);
                        return existing.matches(sku, count);
                    }
                    if (existing != null) {
                        if (deleteReservation(connection, reservationId) != 1) {
                            connection.rollback();
                            restoreAutoCommit(connection, autoCommit);
                            continue;
                        }
                        increment(connection, existing.sku(), existing.count());
                    }
                    if (decrementIfAvailable(connection, sku, count) != 1) {
                        connection.rollback();
                        restoreAutoCommit(connection, autoCommit);
                        return false;
                    }
                    insertReservation(connection, reservationId, sku, count, expiresAt(clock.instant()));
                    connection.commit();
                    restoreAutoCommit(connection, autoCommit);
                    return true;
                } catch (SQLException error) {
                    rollbackQuietly(connection);
                    restoreAutoCommit(connection, autoCommit);
                    if (!retryable(error) || attempt + 1 == MAX_TRANSACTION_ATTEMPTS) {
                        throw error;
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to reserve shop stock for " + reservationId, e);
            }
        }
        throw new IllegalStateException("Shop stock reservation contention exceeded retry limit");
    }

    @Override
    public void release(String sku, int count) {
        requireSku(sku);
        validateCount(count);
        try (Connection connection = dataSource.getConnection()) {
            if (increment(connection, sku, count) == 0) {
                insertStock(connection, sku, count);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to release shop stock for " + sku, e);
        }
    }

    @Override
    public void release(String reservationId, String sku, int count) {
        requireSku(sku);
        validateCount(count);
        if (reservationId == null || reservationId.isBlank()) {
            release(sku, count);
            return;
        }
        requireReservationId(reservationId);
        for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                boolean autoCommit = begin(connection);
                try {
                    Reservation existing = findReservation(connection, reservationId);
                    if (existing == null || !existing.matches(sku, count)) {
                        connection.commit();
                        restoreAutoCommit(connection, autoCommit);
                        return;
                    }
                    if (deleteReservation(connection, reservationId) != 1) {
                        connection.rollback();
                        restoreAutoCommit(connection, autoCommit);
                        continue;
                    }
                    increment(connection, sku, count);
                    connection.commit();
                    restoreAutoCommit(connection, autoCommit);
                    return;
                } catch (SQLException error) {
                    rollbackQuietly(connection);
                    restoreAutoCommit(connection, autoCommit);
                    if (!retryable(error) || attempt + 1 == MAX_TRANSACTION_ATTEMPTS) {
                        throw error;
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to release shop reservation " + reservationId, e);
            }
        }
    }

    @Override
    public int remaining(String sku) {
        requireSku(sku);
        String sql = "SELECT remaining FROM " + stockTable + " WHERE sku = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sku);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read shop stock for " + sku, e);
        }
    }

    @Override
    public int reservationCount() {
        String sql = "SELECT COUNT(*) FROM " + reservationTable;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count shop reservations", e);
        }
    }

    @Override
    public int reapExpiredReservations() {
        long now = clock.millis();
        List<String> expired = expiredReservationIds(now);
        int reaped = 0;
        for (String reservationId : expired) {
            if (releaseExpired(reservationId, now)) {
                reaped++;
            }
        }
        return reaped;
    }

    private List<String> expiredReservationIds(long now) {
        String sql = "SELECT reservation_id FROM " + reservationTable + " WHERE expires_at <= ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            try (ResultSet result = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (result.next()) {
                    ids.add(result.getString(1));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to scan expired shop reservations", e);
        }
    }

    private boolean releaseExpired(String reservationId, long now) {
        for (int attempt = 0; attempt < MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                boolean autoCommit = begin(connection);
                try {
                    Reservation existing = findReservation(connection, reservationId);
                    if (existing == null || existing.expiresAtMillis() > now) {
                        connection.commit();
                        restoreAutoCommit(connection, autoCommit);
                        return false;
                    }
                    if (deleteReservation(connection, reservationId) != 1) {
                        connection.rollback();
                        restoreAutoCommit(connection, autoCommit);
                        continue;
                    }
                    increment(connection, existing.sku(), existing.count());
                    connection.commit();
                    restoreAutoCommit(connection, autoCommit);
                    return true;
                } catch (SQLException error) {
                    rollbackQuietly(connection);
                    restoreAutoCommit(connection, autoCommit);
                    if (!retryable(error) || attempt + 1 == MAX_TRANSACTION_ATTEMPTS) {
                        throw error;
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to reap shop reservation " + reservationId, e);
            }
        }
        return false;
    }

    private Reservation findReservation(Connection connection, String reservationId) throws SQLException {
        String sql = "SELECT sku, reserved_count, expires_at FROM " + reservationTable
                + " WHERE reservation_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reservationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new Reservation(result.getString(1), result.getInt(2), result.getLong(3))
                        : null;
            }
        }
    }

    private int decrementIfAvailable(Connection connection, String sku, int count) throws SQLException {
        String sql = "UPDATE " + stockTable + " SET remaining = remaining - ?"
                + " WHERE sku = ? AND remaining >= ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, count);
            statement.setString(2, sku);
            statement.setInt(3, count);
            return statement.executeUpdate();
        }
    }

    private int increment(Connection connection, String sku, int count) throws SQLException {
        String sql = "UPDATE " + stockTable + " SET remaining = remaining + ? WHERE sku = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, count);
            statement.setString(2, sku);
            return statement.executeUpdate();
        }
    }

    private int updateRemaining(Connection connection, String sku, int count) throws SQLException {
        String sql = "UPDATE " + stockTable + " SET remaining = ? WHERE sku = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, count);
            statement.setString(2, sku);
            return statement.executeUpdate();
        }
    }

    private void insertStock(Connection connection, String sku, int count) throws SQLException {
        String sql = "INSERT INTO " + stockTable + " (sku, remaining) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sku);
            statement.setInt(2, count);
            statement.executeUpdate();
        }
    }

    private void insertReservation(
            Connection connection,
            String reservationId,
            String sku,
            int count,
            long expiresAtMillis
    ) throws SQLException {
        String sql = "INSERT INTO " + reservationTable
                + " (reservation_id, sku, reserved_count, expires_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reservationId);
            statement.setString(2, sku);
            statement.setInt(3, count);
            statement.setLong(4, expiresAtMillis);
            statement.executeUpdate();
        }
    }

    private int deleteReservation(Connection connection, String reservationId) throws SQLException {
        String sql = "DELETE FROM " + reservationTable + " WHERE reservation_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reservationId);
            return statement.executeUpdate();
        }
    }

    private long expiresAt(Instant now) {
        if (reservationTtl.isZero()) {
            return Long.MAX_VALUE;
        }
        try {
            return now.plus(reservationTtl).toEpochMilli();
        } catch (ArithmeticException error) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean begin(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        return autoCommit;
    }

    private static boolean retryable(SQLException error) {
        if (error instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = error.getSQLState();
        return state != null && (state.startsWith("23") || state.startsWith("40"));
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

    private static String requireTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        if (!TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid shop stock table name: " + tableName);
        }
        return tableName;
    }

    private static String requireSku(String sku) {
        Objects.requireNonNull(sku, "sku");
        if (sku.isBlank() || sku.length() > 255) {
            throw new IllegalArgumentException("sku must contain 1 to 255 characters");
        }
        return sku;
    }

    private static void requireReservationId(String reservationId) {
        if (reservationId.length() > 255) {
            throw new IllegalArgumentException("reservationId is longer than 255 characters");
        }
    }

    private static void validateCount(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    private record Reservation(String sku, int count, long expiresAtMillis) {
        private boolean matches(String sku, int count) {
            return this.sku.equals(sku) && this.count == count;
        }

        private boolean expiredAt(Instant now) {
            return expiresAtMillis <= now.toEpochMilli();
        }
    }
}
