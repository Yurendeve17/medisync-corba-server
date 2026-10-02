package mz.hospital.server;

import Hospital.Appointment;
import Hospital.AppointmentServicePOA;
import mz.hospital.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public class AppointmentServiceImpl extends AppointmentServicePOA {

    public static final String AGENDADA = "AGENDADA";
    public static final String AGUARDANDO = "AGUARDANDO";
    public static final String CHAMADA = "CHAMADA";
    public static final String EM_ATENDIMENTO = "EM_ATENDIMENTO";
    public static final String CONCLUIDA = "CONCLUIDA";
    public static final String CANCELADA = "CANCELADA";
    public static final String FALTOU = "FALTOU";

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public Appointment scheduleAppointment(
            int patientId,
            String doctor,
            String appointmentDate,
            String specialty
    ) {
        validateInput(patientId, doctor, appointmentDate, specialty);

        String normalizedDoctor = doctor.trim();
        String normalizedDate = appointmentDate.trim();
        String normalizedSpecialty = specialty.trim();

        validateAppointmentDate(normalizedDate);

        try (Connection connection = DatabaseManager.getConnection()) {
            if (!patientExists(connection, patientId)) {
                throw new IllegalArgumentException(
                        "Paciente não encontrado: #" + patientId
                );
            }

            if (appointmentExists(connection, normalizedDoctor, normalizedDate)) {
                throw new IllegalArgumentException(
                        "O médico já possui uma consulta agendada para "
                                + normalizedDate + "."
                );
            }

            String sql = """
                    INSERT INTO appointments
                    (patient_id, doctor, appointment_date, specialty)
                    VALUES (?, ?, ?, ?)
                    """;

            try (PreparedStatement statement = connection.prepareStatement(
                    sql,
                    java.sql.Statement.RETURN_GENERATED_KEYS
            )) {
                statement.setInt(1, patientId);
                statement.setString(2, normalizedDoctor);
                statement.setString(3, normalizedDate);
                statement.setString(4, normalizedSpecialty);

                statement.executeUpdate();

                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        return new Appointment(
                                keys.getInt(1),
                                patientId,
                                normalizedDoctor,
                                normalizedDate,
                                normalizedSpecialty,
                                AGENDADA
                        );
                    }
                }
            }

            throw new RuntimeException(
                    "Não foi possível obter o ID da consulta."
            );

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao marcar consulta.",
                    e
            );
        }
    }

    private void validateInput(
            int patientId,
            String doctor,
            String appointmentDate,
            String specialty
    ) {
        if (patientId <= 0) {
            throw new IllegalArgumentException("O ID do paciente é inválido.");
        }
        if (doctor == null || doctor.isBlank()) {
            throw new IllegalArgumentException("O médico é obrigatório.");
        }
        if (appointmentDate == null || appointmentDate.isBlank()) {
            throw new IllegalArgumentException("A data e hora da consulta são obrigatórias.");
        }
        if (specialty == null || specialty.isBlank()) {
            throw new IllegalArgumentException("A especialidade é obrigatória.");
        }
    }

    private void validateAppointmentDate(String appointmentDate) {
        try {
            LocalDateTime.parse(appointmentDate, DATE_TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Data/hora inválida. Use o formato yyyy-MM-dd HH:mm."
            );
        }
    }

    private boolean patientExists(Connection connection, int patientId)
            throws Exception {
        String sql = "SELECT 1 FROM patients WHERE id = ? LIMIT 1";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, patientId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean appointmentExists(
            Connection connection,
            String doctor,
            String appointmentDate
    ) throws Exception {
        String sql = """
                SELECT 1
                FROM appointments
                WHERE doctor = ?
                  AND appointment_date = ?
                LIMIT 1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, doctor);
            statement.setString(2, appointmentDate);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    public Appointment findAppointmentById(int id) {

        String sql = """
                SELECT id, patient_id, doctor, appointment_date, specialty, status
                FROM appointments
                WHERE id = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, id);

            try (ResultSet result = statement.executeQuery()) {

                if (result.next()) {
                    return new Appointment(
                            result.getInt("id"),
                            result.getInt("patient_id"),
                            result.getString("doctor"),
                            result.getString("appointment_date"),
                            result.getString("specialty"),
                            result.getString("status")
                    );
                }
            }

            return new Appointment(0, 0, "", "", "", "");

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao procurar consulta.",
                    e
            );
        }
    }

    @Override
    public Appointment[] listAppointments() {

        String sql = """
                SELECT id, patient_id, doctor, appointment_date, specialty, status
                FROM appointments
                ORDER BY appointment_date, id
                """;

        List<Appointment> appointments = new ArrayList<>();

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                appointments.add(new Appointment(
                        result.getInt("id"),
                        result.getInt("patient_id"),
                        result.getString("doctor"),
                        result.getString("appointment_date"),
                        result.getString("specialty"),
                        result.getString("status")
                ));
            }

            return appointments.toArray(new Appointment[0]);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao listar consultas.",
                    e
            );
        }
    }
    @Override
    public void updateAppointmentStatus(int id, String status) {
        if (id <= 0) {
            throw new IllegalArgumentException("O ID da consulta é inválido.");
        }

        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!isValidStatus(normalized)) {
            throw new IllegalArgumentException("Estado de consulta inválido: " + status);
        }

        try (Connection connection = DatabaseManager.getConnection()) {
            String currentStatus = null;
            String selectSql = "SELECT status FROM appointments WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                statement.setInt(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        currentStatus = result.getString("status");
                    } else {
                        throw new IllegalArgumentException("Consulta não encontrada: #" + id);
                    }
                }
            }

            if (!isValidTransition(currentStatus, normalized)) {
                throw new IllegalArgumentException(
                        "Transição de estado inválida: " + currentStatus + " -> " + normalized
                );
            }

            String sql = "UPDATE appointments SET status = ? WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, normalized);
                statement.setInt(2, id);
                statement.executeUpdate();
            }

            // Mantém a fila sincronizada com o ciclo da consulta.
            String queueSql = "UPDATE queue SET status = ? WHERE appointment_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(queueSql)) {
                statement.setString(1, normalized);
                statement.setInt(2, id);
                statement.executeUpdate();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao atualizar estado da consulta.", e);
        }
    }

    private boolean isValidStatus(String status) {
        return AGENDADA.equals(status) || AGUARDANDO.equals(status)
                || CHAMADA.equals(status) || EM_ATENDIMENTO.equals(status)
                || CONCLUIDA.equals(status) || CANCELADA.equals(status)
                || FALTOU.equals(status);
    }

    private boolean isValidTransition(String current, String next) {
        if (current == null || current.isBlank()) return AGENDADA.equals(next);
        if (current.equals(next)) return true;
        return switch (current) {
            case AGENDADA -> AGUARDANDO.equals(next) || CHAMADA.equals(next) || CANCELADA.equals(next) || FALTOU.equals(next);
            case AGUARDANDO -> CHAMADA.equals(next) || CANCELADA.equals(next) || FALTOU.equals(next);
            case CHAMADA -> EM_ATENDIMENTO.equals(next) || AGUARDANDO.equals(next);
            case EM_ATENDIMENTO -> CONCLUIDA.equals(next);
            default -> false;
        };
    }

}
