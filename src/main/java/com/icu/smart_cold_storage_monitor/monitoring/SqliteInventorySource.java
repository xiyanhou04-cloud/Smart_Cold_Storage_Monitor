package com.icu.smart_cold_storage_monitor.monitoring;

import static com.icu.smart_cold_storage_monitor.monitoring.MonitoringModels.*;
import java.nio.file.Files;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Opens the external inventory file read-only. Never uses the application authentication database. */
@Repository
public class SqliteInventorySource implements InventorySource {
    private final MonitoringProperties properties;
    public SqliteInventorySource(MonitoringProperties properties) { this.properties = properties; }
    public List<Lot> loadStock(Instant asOf) {
        var path = properties.getInventoryPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Inventory database is unavailable.");
        String sql = """
            SELECT l.id,l.lot_code,p.sku,p.name_en,p.unit,lc.code AS location,l.quality_status,
                   l.received_at,l.demo_use_by_at,p.temp_min_c,p.temp_max_c,p.rh_min_pct,p.rh_max_pct,
                   p.condition_note,s.url,
                   SUM(CASE m.operation WHEN 'IN' THEN m.quantity ELSE -m.quantity END) AS stock
            FROM lots l JOIN products p ON p.id=l.product_id
            JOIN locations lc ON lc.id=l.location_id
            JOIN movements m ON m.lot_id=l.id
            LEFT JOIN sources s ON s.id=p.source_id
            WHERE julianday(m.operated_at)<=julianday(?)
              AND julianday(l.received_at)<=julianday(?)
            GROUP BY l.id HAVING stock>0 ORDER BY p.sku,l.lot_code
            """;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toUri() + "?mode=ro")) {
            try (Statement setup = c.createStatement()) { setup.execute("PRAGMA query_only=ON"); }
            try (PreparedStatement q = c.prepareStatement(sql)) {
                q.setString(1, asOf.toString()); q.setString(2, asOf.toString());
                List<Lot> result = new ArrayList<>();
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) result.add(new Lot(rs.getLong("id"),rs.getString("lot_code"),
                        rs.getString("sku"),rs.getString("name_en"),rs.getString("unit"),rs.getLong("stock"),
                        rs.getString("location"),rs.getString("quality_status"),
                        Instant.parse(rs.getString("received_at")),Instant.parse(rs.getString("demo_use_by_at")),
                        number(rs,"temp_min_c"),number(rs,"temp_max_c"),number(rs,"rh_min_pct"),number(rs,"rh_max_pct"),
                        rs.getString("condition_note"),rs.getString("url")));
                }
                return List.copyOf(result);
            }
        } catch (SQLException ex) { throw new IllegalStateException("Inventory schema or query is unavailable.", ex); }
    }
    private static Double number(ResultSet r, String column) throws SQLException {
        double n = r.getDouble(column); return r.wasNull() ? null : n;
    }
}
