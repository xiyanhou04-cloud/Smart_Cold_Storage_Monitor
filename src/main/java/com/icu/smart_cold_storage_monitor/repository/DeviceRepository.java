package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.Device;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for the {@code devices} table. */
@Repository
public class DeviceRepository {

    private final JdbcClient jdbc;

    public DeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Device> findById(String id) {
        return jdbc.sql("SELECT id, name, enabled, location_code FROM devices WHERE id = :id")
                .param("id", id)
                .query(DeviceRepository::mapRow)
                .optional();
    }

    /** Ids of every registered device (enabled or not), in id order. */
    public List<String> findAllIds() {
        return jdbc.sql("SELECT id FROM devices ORDER BY id").query(String.class).list();
    }

    /**
     * Inserts the device if it does not exist. An existing row keeps its name, enabled flag, and location; only a
     * missing (NULL) location is filled in, e.g. for a device registered before {@code location_code} existed.
     * {@code locationCode} may be {@code null} when unknown.
     */
    public void insertIfAbsent(String id, String name, String locationCode) {
        jdbc.sql("""
                INSERT INTO devices (id, name, enabled, location_code) VALUES (:id, :name, 1, :location)
                ON CONFLICT (id) DO UPDATE SET location_code = coalesce(devices.location_code, excluded.location_code)""")
                .param("id", id)
                .param("name", name)
                .param("location", locationCode)
                .update();
    }

    private static Device mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Device(rs.getString("id"), rs.getString("name"), rs.getInt("enabled") == 1,
                rs.getString("location_code"));
    }
}
