package mz.hospital.server;

import Hospital.Appointment;
import Hospital.AppointmentServicePOA;
import mz.hospital.server.db.DatabaseManager;
import mz.hospital.server.db.AuditLogger;

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
    public static final String CONFIRMADA = "CONFIRMADA";
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
            connection.setAutoCommit(false);
            if (!patientExists(connection, patientId)) {
                throw new IllegalArgumentException(
                        "Paciente não encontrado: #" + patientId
                );
            }

            if (!doctorBelongsToSpecialty(connection, normalizedDoctor, normalizedSpecialty)) {
                throw new IllegalArgumentException(
                        "O médico seleccionado não pertence à especialidade "
                                + normalizedSpecialty + "."
                );
            }

            validateWorkingHours(connection, normalizedDoctor, normalizedDate);
            if (doctorHasAppointmentWithinSlot(connection, normalizedDoctor, normalizedDate)) {
                throw new IllegalArgumentException(
                        "O médico já possui uma consulta incompatível com o intervalo de 30 minutos em "
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
                        int generatedId = keys.getInt(1);
                        connection.commit();
                        return new Appointment(
                                generatedId,
                                patientId,
                                "",
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
            LocalDateTime dateTime = LocalDateTime.parse(appointmentDate, DATE_TIME_FORMAT);
            if (dateTime.isBefore(LocalDateTime.now(hospitalZone())))
                throw new IllegalArgumentException("Não é possível agendar uma consulta no passado.");
            if (dateTime.getMinute() != 0 && dateTime.getMinute() != 30) {
                throw new IllegalArgumentException(
                        "As consultas só podem ser marcadas em intervalos de 30 minutos (ex.: 09:00, 09:30, 10:00)."
                );
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Data/hora inválida. Use o formato yyyy-MM-dd HH:mm."
            );
        }
    }

    private boolean doctorBelongsToSpecialty(
            Connection connection,
            String doctor,
            String specialty
    ) throws Exception {
        String lookup = """
                SELECT d.full_name, s.name
                FROM doctors d
                JOIN specialties s ON s.id = d.specialty_id
                WHERE d.active = 1 AND s.active = 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(lookup);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                if (normalizeDoctor(result.getString(1)).equals(normalizeDoctor(doctor))
                        && normalizeText(result.getString(2)).equals(normalizeText(specialty))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean doctorHasAppointmentWithinSlot(
            Connection connection,
            String doctor,
            String appointmentDate
    ) throws Exception {
        return doctorHasAppointmentWithinSlot(connection, doctor, appointmentDate, -1);
    }

    private boolean doctorHasAppointmentWithinSlot(
            Connection connection,
            String doctor,
            String appointmentDate,
            int excludedAppointmentId
    ) throws Exception {
        LocalDateTime requested = LocalDateTime.parse(appointmentDate, DATE_TIME_FORMAT);
        String sql = """
                SELECT appointment_date
                FROM appointments
                WHERE doctor = ?
                  AND id <> ?
                  AND status NOT IN ('CANCELADA', 'FALTOU')
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, doctor);
            statement.setInt(2, excludedAppointmentId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    try {
                        LocalDateTime existing = LocalDateTime.parse(result.getString(1), DATE_TIME_FORMAT);
                        long minutes = Math.abs(java.time.Duration.between(existing, requested).toMinutes());
                        if (minutes < 30) return true;
                    } catch (DateTimeParseException ignored) {
                        // Dados antigos inválidos não devem impedir novas marcações.
                    }
                }
            }
        }
        return false;
    }

    private void validateWorkingHours(Connection connection, String doctor, String appointmentDate) throws Exception {
        String days = "", start = "", end = "";
        try (PreparedStatement ps = connection.prepareStatement("SELECT full_name,working_days,start_time,end_time FROM doctors WHERE active=1")) {
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) if (normalizeDoctor(r.getString(1)).equals(normalizeDoctor(doctor))) {
                    days = r.getString(2); start = r.getString(3); end = r.getString(4); break;
                }
            }
        }
        if (days == null || days.isBlank()) return;
        LocalDateTime requested = LocalDateTime.parse(appointmentDate, DATE_TIME_FORMAT);
        String[] dayNames = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
        String day = dayNames[requested.getDayOfWeek().getValue() - 1];
        boolean dayAllowed = java.util.Arrays.stream(days.split(",")).map(String::trim).anyMatch(day::equalsIgnoreCase);
        if (!dayAllowed) throw new IllegalArgumentException("O médico não atende nesse dia da semana.");
        try {
            java.time.LocalTime from = java.time.LocalTime.parse(start);
            java.time.LocalTime until = java.time.LocalTime.parse(end);
            java.time.LocalTime slotEnd = requested.toLocalTime().plusMinutes(30);
            if (requested.toLocalTime().isBefore(from) || slotEnd.isAfter(until))
                throw new IllegalArgumentException("O horário está fora do período de atendimento do médico (" + start + "-" + end + ").");
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalStateException("O horário de atendimento do médico está mal configurado.", e);
        }
    }

    private String normalizeText(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .trim().replaceAll("\\s+", " ")
                .toLowerCase();
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

    @Override
    public Appointment findAppointmentById(int id) {

        String sql = """
                SELECT a.id, a.patient_id, p.full_name patient_name, a.doctor, a.appointment_date, a.specialty, a.status
                FROM appointments a JOIN patients p ON p.id=a.patient_id
                WHERE a.id = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, id);

            try (ResultSet result = statement.executeQuery()) {

                if (result.next()) {
                    return new Appointment(
                            result.getInt("id"),
                            result.getInt("patient_id"),
                            result.getString("patient_name"),
                            result.getString("doctor"),
                            result.getString("appointment_date"),
                            result.getString("specialty"),
                            result.getString("status")
                    );
                }
            }

            return new Appointment(0, 0, "", "", "", "", "");

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
                SELECT a.id, a.patient_id, p.full_name patient_name, a.doctor, a.appointment_date, a.specialty, a.status
                FROM appointments a JOIN patients p ON p.id=a.patient_id
                ORDER BY a.appointment_date, a.id
                """;

        List<Appointment> appointments = new ArrayList<>();

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                appointments.add(new Appointment(
                        result.getInt("id"),
                        result.getInt("patient_id"),
                        result.getString("patient_name"),
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
    public Appointment rescheduleAppointment(int id, String appointmentDate) { return rescheduleAppointmentWithReason(id, appointmentDate, ""); }

    @Override
    public Appointment rescheduleAppointmentWithReason(int id, String appointmentDate, String reason) {
        if (id <= 0) {
            throw new IllegalArgumentException("O ID da consulta é inválido.");
        }
        if (appointmentDate == null || appointmentDate.isBlank()) {
            throw new IllegalArgumentException("A nova data e hora são obrigatórias.");
        }

        String normalizedDate = appointmentDate.trim();
        validateAppointmentDate(normalizedDate);

        try (Connection connection = DatabaseManager.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String doctor;
                String specialty;
                String currentStatus;
                String select = "SELECT doctor, specialty, status FROM appointments WHERE id = ?";
                try (PreparedStatement statement = connection.prepareStatement(select)) {
                    statement.setInt(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalArgumentException("Consulta não encontrada: #" + id);
                        }
                        doctor = result.getString("doctor");
                        specialty = result.getString("specialty");
                        currentStatus = result.getString("status");
                    }
                }

                if (!AGENDADA.equals(currentStatus)) {
                    throw new IllegalArgumentException(
                            "Só é possível reagendar consultas no estado AGENDADA."
                    );
                }

                if (doctorHasAppointmentWithinSlot(connection, doctor, normalizedDate, id)) {
                    throw new IllegalArgumentException(
                            "O médico já possui uma consulta incompatível com o intervalo de 30 minutos em " + normalizedDate + "."
                    );
                }
                validateWorkingHours(connection, doctor, normalizedDate);

                String update = "UPDATE appointments SET appointment_date = ?,change_reason=? WHERE id = ?";
                try (PreparedStatement statement = connection.prepareStatement(update)) {
                    statement.setString(1, normalizedDate);
                    statement.setString(2, reason == null ? "" : reason.trim());
                    statement.setInt(3, id);
                    statement.executeUpdate();
                }

                AuditLogger.log(connection, null, "", "APPOINTMENT_RESCHEDULED", "APPOINTMENT", id, reason);

                connection.commit();
                return findAppointmentById(id);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao reagendar consulta.", e);
        }
    }

    @Override
    public void cancelAppointment(int id, String reason) {
        if (id <= 0) throw new IllegalArgumentException("O ID da consulta é inválido.");
        String why = reason == null ? "" : reason.trim();
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                String current;
                try (PreparedStatement ps = c.prepareStatement("SELECT status FROM appointments WHERE id=?")) {
                    ps.setInt(1, id); try (ResultSet r = ps.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Consulta não encontrada: #" + id);
                        current = r.getString(1);
                    }
                }
                if (!AGENDADA.equals(current) && !CONFIRMADA.equals(current) && !AGUARDANDO.equals(current))
                    throw new IllegalArgumentException("A consulta não pode ser cancelada no estado " + current + ".");
                try (PreparedStatement ps = c.prepareStatement("UPDATE appointments SET status='CANCELADA',cancel_reason=? WHERE id=? AND status=?")) {
                    ps.setString(1, why); ps.setInt(2, id); ps.setString(3, current);
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("A consulta foi alterada por outra operação.");
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE queue SET status='CANCELADA' WHERE appointment_id=? AND status IN ('AGUARDANDO','CHAMADA')")) {
                    ps.setInt(1, id); ps.executeUpdate();
                }
                AuditLogger.log(c, null, "", "APPOINTMENT_CANCELLED", "APPOINTMENT", id, why);
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao cancelar consulta.", e); }
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
            connection.setAutoCommit(false);
            try {
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

                if (CHAMADA.equals(normalized) && !queueHasStatus(connection, id, AGUARDANDO)) {
                    throw new IllegalArgumentException(
                            "A consulta deve estar na fila em estado AGUARDANDO antes de ser chamada."
                    );
                }

                if (EM_ATENDIMENTO.equals(normalized)) {
                    String doctor = findDoctor(connection, id);
                    if (doctorHasActiveAppointment(connection, doctor, id)) {
                        throw new IllegalArgumentException(
                                "O médico já está a atender outro paciente."
                        );
                    }
                    if (!queueHasStatus(connection, id, CHAMADA)) {
                        throw new IllegalArgumentException(
                                "A consulta deve estar em CHAMADA antes de iniciar o atendimento."
                        );
                    }
                }

                if (CONCLUIDA.equals(normalized) && !queueHasStatus(connection, id, EM_ATENDIMENTO)) {
                    throw new IllegalArgumentException(
                            "A consulta deve estar EM_ATENDIMENTO antes de ser concluída."
                    );
                }

                String sql = "UPDATE appointments SET status = ? WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, normalized);
                statement.setInt(2, id);
                statement.executeUpdate();
            }

                // Uma consulta cancelada deixa de ocupar a fila.
                if (CANCELADA.equals(normalized)) {
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE queue SET status='CANCELADA' WHERE appointment_id=? AND status IN ('AGUARDANDO','CHAMADA')")) { statement.setInt(1, id); statement.executeUpdate(); }
                } else {
                    // Mantém a fila sincronizada com o ciclo da consulta.
                    String queueSql = "UPDATE queue SET status = ? WHERE appointment_id = ?";
                        try (PreparedStatement statement = connection.prepareStatement(queueSql)) {
                        statement.setString(1, normalized);
                        statement.setInt(2, id);
                        statement.executeUpdate();
                    }
                }
                String timestampColumn = switch (normalized) {
                    case CHAMADA -> "called_at";
                    case EM_ATENDIMENTO -> "started_at";
                    case CONCLUIDA -> "completed_at";
                    default -> null;
                };
                if (timestampColumn != null) {
                    String now = java.time.ZonedDateTime.now(hospitalZone()).toString();
                    try (PreparedStatement ps = connection.prepareStatement("UPDATE queue SET " + timestampColumn + "=? WHERE appointment_id=?")) { ps.setString(1, now); ps.setInt(2, id); ps.executeUpdate(); }
                }
                AuditLogger.log(connection, null, "", "APPOINTMENT_STATE_CHANGED", "APPOINTMENT", id, currentStatus + " -> " + normalized);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao atualizar estado da consulta.", e);
        }
    }

    private boolean queueHasStatus(Connection connection, int appointmentId, String status) throws Exception {
        String sql = "SELECT 1 FROM queue WHERE appointment_id = ? AND status = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, appointmentId);
            statement.setString(2, status);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private String findDoctor(Connection connection, int appointmentId) throws Exception {
        String sql = "SELECT doctor FROM appointments WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, appointmentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Consulta não encontrada: #" + appointmentId);
                return result.getString(1);
            }
        }
    }

    private boolean doctorHasActiveAppointment(Connection connection, String doctor, int appointmentId) throws Exception {
        String sql = "SELECT doctor FROM appointments WHERE status = 'EM_ATENDIMENTO' AND id <> ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, appointmentId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (normalizeDoctor(result.getString(1)).equals(normalizeDoctor(doctor))) return true;
                }
                return false;
            }
        }
    }

    private String normalizeDoctor(String value) {
        if (value == null) return "";
        String text = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        text = text.replaceAll("\\b(dr|dra|doutor|doutora)\\b\\.?", " ");
        return text.trim().replaceAll("\\s+", " ");
    }

    private boolean isValidStatus(String status) {
        return AGENDADA.equals(status) || CONFIRMADA.equals(status) || AGUARDANDO.equals(status)
                || CHAMADA.equals(status) || EM_ATENDIMENTO.equals(status)
                || CONCLUIDA.equals(status) || CANCELADA.equals(status)
                || FALTOU.equals(status) || "DESISTIU".equals(status);
    }

    private boolean isValidTransition(String current, String next) {
        if (current == null || current.isBlank()) return AGENDADA.equals(next);
        if (current.equals(next)) return true;
        return switch (current) {
            case AGENDADA -> CONFIRMADA.equals(next) || CANCELADA.equals(next) || FALTOU.equals(next);
            case CONFIRMADA -> AGUARDANDO.equals(next) || CANCELADA.equals(next) || FALTOU.equals(next);
            case AGUARDANDO -> CHAMADA.equals(next) || CANCELADA.equals(next) || FALTOU.equals(next);
            case CHAMADA -> EM_ATENDIMENTO.equals(next) || AGUARDANDO.equals(next);
            case EM_ATENDIMENTO -> CONCLUIDA.equals(next);
            case "DESISTIU" -> false;
            default -> false;
        };
    }

    private static java.time.ZoneId hospitalZone() {
        try { return java.time.ZoneId.of(System.getenv().getOrDefault("HOSPITAL_TIMEZONE", "Africa/Maputo")); }
        catch (Exception e) { return java.time.ZoneId.of("Africa/Maputo"); }
    }

}
