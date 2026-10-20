package com.commonbattle.game.profile;

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
import java.util.regex.Pattern;

/**
 * JDBC Profile 投影字节存储。
 */
public final class JdbcProfileProjectionBytesStore implements ProfileProjectionBytesStore {
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final String tableName;

    public JdbcProfileProjectionBytesStore(DataSource dataSource) {
        this(dataSource, "profile_projection");
    }

    public JdbcProfileProjectionBytesStore(DataSource dataSource, String tableName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (!TABLE_NAME.matcher(Objects.requireNonNull(tableName, "tableName")).matches()) {
            throw new IllegalArgumentException("Invalid profile projection table name: " + tableName);
        }
        this.tableName = tableName;
    }

    public void initializeSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName
                + " (projection_key VARCHAR(64) PRIMARY KEY, payload BLOB NOT NULL)";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize profile projection table", e);
        }
    }

    @Override
    public void save(String key, byte[] payload) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        try (Connection connection = dataSource.getConnection()) {
            if (update(connection, key, payload) == 0) {
                insert(connection, key, payload);
            }
        } catch (SQLIntegrityConstraintViolationException race) {
            try (Connection connection = dataSource.getConnection()) {
                if (update(connection, key, payload) == 0) {
                    throw new IllegalStateException("Profile projection row disappeared " + key, race);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to update profile projection " + key, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save profile projection " + key, e);
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
            throw new IllegalStateException("Failed to load profile projections", e);
        }
    }

    private int update(Connection connection, String key, byte[] payload) throws SQLException {
        String sql = "UPDATE " + tableName + " SET payload = ? WHERE projection_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, Arrays.copyOf(payload, payload.length));
            statement.setString(2, key);
            return statement.executeUpdate();
        }
    }

    private void insert(Connection connection, String key, byte[] payload) throws SQLException {
        String sql = "INSERT INTO " + tableName + " (projection_key, payload) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setBytes(2, Arrays.copyOf(payload, payload.length));
            statement.executeUpdate();
        }
    }
}
