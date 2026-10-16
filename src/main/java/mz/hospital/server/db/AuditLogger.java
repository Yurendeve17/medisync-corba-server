package mz.hospital.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class AuditLogger {
    private AuditLogger() {}

    public static void log(Connection c, Integer userId, String username, String action,
                           String entityType, Integer entityId, String details) throws Exception {
        String zoneName = System.getenv().getOrDefault("HOSPITAL_TIMEZONE", "Africa/Maputo");
        ZoneId zone;
        try { zone = ZoneId.of(zoneName); } catch (Exception ignored) { zone = ZoneId.of("Africa/Maputo"); }
        String now = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO audit_log(user_id,username,action,entity_type,entity_id,details,created_at)
                VALUES(?,?,?,?,?,?,?)
                """)) {
            if (userId == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setInt(1, userId);
            ps.setString(2, username == null ? "" : username);
            ps.setString(3, action); ps.setString(4, entityType);
            if (entityId == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, entityId);
            ps.setString(6, details == null ? "" : details); ps.setString(7, now);
            ps.executeUpdate();
        }
    }
}
