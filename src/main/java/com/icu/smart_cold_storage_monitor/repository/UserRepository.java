package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.User;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for the {@code users} table. */
@Repository
public class UserRepository {

    private static final String COLUMNS = "id, username, password_hash, role, enabled, created_at";

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByUsername(String username) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE username = :username")
                .param("username", username)
                .query(UserRepository::mapRow)
                .optional();
    }

    public Optional<User> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id")
                .param("id", id)
                .query(UserRepository::mapRow)
                .optional();
    }

    public boolean existsByUsername(String username) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE username = :username)")
                .param("username", username)
                .query(Boolean.class)
                .single();
    }

    public List<User> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users ORDER BY id")
                .query(UserRepository::mapRow)
                .list();
    }

    public void updatePasswordHash(long id, String passwordHash) {
        jdbc.sql("UPDATE users SET password_hash = :passwordHash WHERE id = :id")
                .param("passwordHash", passwordHash)
                .param("id", id)
                .update();
    }

    public void updateEnabled(long id, boolean enabled) {
        jdbc.sql("UPDATE users SET enabled = :enabled WHERE id = :id")
                .param("enabled", enabled ? 1 : 0)
                .param("id", id)
                .update();
    }

    public boolean existsByRole(Role role) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE role = :role)")
                .param("role", role.name())
                .query(Boolean.class)
                .single();
    }

    /** Inserts a user and returns the generated id. */
    public long insert(String username, String passwordHash, Role role, boolean enabled, Instant createdAt) {
        return jdbc.sql("""
                        INSERT INTO users (username, password_hash, role, enabled, created_at)
                        VALUES (:username, :passwordHash, :role, :enabled, :createdAt)
                        RETURNING id
                        """)
                .param("username", username)
                .param("passwordHash", passwordHash)
                .param("role", role.name())
                .param("enabled", enabled ? 1 : 0)
                .param("createdAt", SqlTimestamps.format(createdAt))
                .query(Long.class)
                .single();
    }

    private static User mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")),
                rs.getInt("enabled") == 1,
                SqlTimestamps.parse(rs.getString("created_at")));
    }
}
