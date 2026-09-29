package mz.hospital.server;

import Hospital.Notification;
import Hospital.NotificationServicePOA;
import mz.hospital.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Notificações "médico chamou paciente" para a recepção.
 * Persistidas na tabela notifications (SQLite), tal como os restantes serviços.
 */
public class NotificationServiceImpl extends NotificationServicePOA {

    private static final DateTimeFormatter ISO_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final ZoneId ZONE = resolveZone();

    /**
     * O contentor corre normalmente em UTC; as horas mostradas na recepção
     * devem ser as do hospital. Configurável com HOSPITAL_TIMEZONE.
     */
    private static ZoneId resolveZone() {
        String configured = System.getenv("HOSPITAL_TIMEZONE");

        if (configured != null && !configured.isBlank()) {
            try {
                return ZoneId.of(configured.trim());
            } catch (Exception e) {
                System.err.println(
                        "HOSPITAL_TIMEZONE inválido: " + configured
                                + " (a usar Africa/Maputo)"
                );
            }
        }

        return ZoneId.of("Africa/Maputo");
    }

    @Override
    public Notification callPatient(
            int appointmentId,
            int patientId,
            String patientName,
            String doctor
    ) {
        String doctorName = doctor == null ? "" : doctor.trim();
        String name = patientName == null ? "" : patientName.trim();

        if (doctorName.isEmpty()) {
            throw new RuntimeException(
                    "O médico é obrigatório para chamar um paciente."
            );
        }

        String createdAt = LocalDateTime.now(ZONE).format(ISO_SECONDS);

        String sql = """
                INSERT INTO notifications
                (appointment_id, patient_id, patient_name, doctor,
                 created_at, is_read)
                VALUES (?, ?, ?, ?, ?, 0)
                """;

        try (Connection connection = DatabaseManager.getConnection()) {

            if (name.isEmpty()) {
                name = findPatientName(connection, patientId);
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            )) {

                statement.setInt(1, appointmentId);
                statement.setInt(2, patientId);
                statement.setString(3, name);
                statement.setString(4, doctorName);
                statement.setString(5, createdAt);

                statement.executeUpdate();

                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        return new Notification(
                                keys.getInt(1),
                                appointmentId,
                                patientId,
                                name,
                                doctorName,
                                createdAt,
                                false
                        );
                    }
                }
            }

            throw new RuntimeException(
                    "Não foi possível obter o ID da notificação."
            );

        } catch (RuntimeException e) {
            throw e;

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao chamar paciente.",
                    e
            );
        }
    }

    @Override
    public Notification[] listNotifications() {

        String sql = """
                SELECT id, appointment_id, patient_id, patient_name,
                       doctor, created_at, is_read
                FROM notifications
                ORDER BY id
                """;

        List<Notification> notifications = new ArrayList<>();

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                notifications.add(new Notification(
                        result.getInt("id"),
                        result.getInt("appointment_id"),
                        result.getInt("patient_id"),
                        result.getString("patient_name"),
                        result.getString("doctor"),
                        result.getString("created_at"),
                        result.getInt("is_read") != 0
                ));
            }

            return notifications.toArray(new Notification[0]);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao listar notificações.",
                    e
            );
        }
    }

    @Override
    public void markAsRead(int id) {

        String sql = """
                UPDATE notifications
                SET is_read = 1
                WHERE id = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, id);
            statement.executeUpdate();

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao marcar notificação como lida.",
                    e
            );
        }
    }

    @Override
    public void markAllAsRead() {

        String sql = """
                UPDATE notifications
                SET is_read = 1
                WHERE is_read = 0
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.executeUpdate();

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao marcar notificações como lidas.",
                    e
            );
        }
    }

    private static String findPatientName(
            Connection connection,
            int patientId
    ) throws Exception {

        String sql = "SELECT full_name FROM patients WHERE id = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, patientId);

            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("full_name") : "";
            }
        }
    }
}
