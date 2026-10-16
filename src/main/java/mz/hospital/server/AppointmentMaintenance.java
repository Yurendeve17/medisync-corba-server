package mz.hospital.server;

import mz.hospital.server.db.AuditLogger;
import mz.hospital.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AppointmentMaintenance {
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "medisync-appointment-maintenance"); t.setDaemon(true); return t;
    });
    private AppointmentMaintenance() {}

    public static void start() {
        EXECUTOR.scheduleAtFixedRate(AppointmentMaintenance::markMissedAppointments, 0, 1, TimeUnit.MINUTES);
    }

    private static void markMissedAppointments() {
        try {
            ZoneId zone;
            try { zone = ZoneId.of(System.getenv().getOrDefault("HOSPITAL_TIMEZONE", "Africa/Maputo")); }
            catch (Exception e) { zone = ZoneId.of("Africa/Maputo"); }
            LocalDate today = LocalDate.now(zone);
            boolean endOfDay = !LocalTime.now(zone).isBefore(LocalTime.of(23, 59));
            try (Connection c = DatabaseManager.getConnection()) {
                c.setAutoCommit(false);
                try {
                    String dueSql = "SELECT id FROM appointments WHERE status='AGENDADA' AND (substr(appointment_date,1,10)<? OR (?=1 AND substr(appointment_date,1,10)=?))";
                    try (PreparedStatement find = c.prepareStatement(dueSql)) {
                        find.setString(1, today.toString()); find.setInt(2, endOfDay ? 1 : 0); find.setString(3, today.toString());
                        try (ResultSet r = find.executeQuery()) {
                            while (r.next()) AuditLogger.log(c, null, "", "APPOINTMENT_MARKED_ABSENT", "APPOINTMENT", r.getInt(1), "Fim do dia sem check-in");
                        }
                    }
                    try (PreparedStatement update = c.prepareStatement("UPDATE appointments SET status='FALTOU' WHERE status='AGENDADA' AND (substr(appointment_date,1,10)<? OR (?=1 AND substr(appointment_date,1,10)=?))")) {
                        update.setString(1, today.toString()); update.setInt(2, endOfDay ? 1 : 0); update.setString(3, today.toString()); update.executeUpdate();
                    }
                    c.commit();
                } catch (Exception e) { c.rollback(); throw e; }
            }
        } catch (Exception e) { System.err.println("Falha ao actualizar consultas sem presença: " + e.getMessage()); }
    }
}
