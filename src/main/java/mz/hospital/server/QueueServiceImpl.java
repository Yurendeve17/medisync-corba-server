package mz.hospital.server;

import Hospital.QueueEntry;
import Hospital.QueueServicePOA;
import mz.hospital.server.db.DatabaseManager;
import mz.hospital.server.db.AuditLogger;
import mz.hospital.server.security.SessionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

public class QueueServiceImpl extends QueueServicePOA {

    @Override
    public void addToQueue(int patientId) {
        if (patientId <= 0) throw new IllegalArgumentException("O ID do paciente é inválido.");
        try (Connection c = DatabaseManager.getConnection()) {
            if (!patientExists(c, patientId)) throw new IllegalArgumentException("Paciente não encontrado: #" + patientId);
            if (alreadyInActiveQueue(c, patientId)) throw new IllegalArgumentException("O paciente #" + patientId + " já está numa fila activa.");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue (appointment_id, patient_id, status, priority,added_at) VALUES (NULL, ?, 'AGUARDANDO', 0,?)")) {
                ps.setInt(1, patientId); ps.setString(2, nowAtHospital()); ps.executeUpdate();
            }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao adicionar paciente à fila.", e); }
    }

    @Override
    public void addAppointmentToQueue(int appointmentId) { addAppointmentToQueueWithPriority(appointmentId, 0); }

    @Override
    public void addAppointmentToQueueWithPriority(int appointmentId, int priority) {
        if (appointmentId <= 0) throw new IllegalArgumentException("O ID da consulta é inválido.");
        validatePriority(priority);
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                AppointmentRow appointment = findAppointment(c, appointmentId);
                if (appointment == null) throw new IllegalArgumentException("Consulta não encontrada: #" + appointmentId);
                if (!AppointmentServiceImpl.AGENDADA.equals(appointment.status)) {
                    throw new IllegalArgumentException(
                    "O check-in só é permitido para consultas no estado AGENDADA."
                    );
                }
                validatePriority(priority);
                java.time.LocalDate appointmentDay = java.time.LocalDateTime.parse(appointment.appointmentDate, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).toLocalDate();
                if (!appointmentDay.equals(java.time.LocalDate.now(hospitalZone()))) throw new IllegalArgumentException("O check-in só pode ser feito no dia da consulta.");
                if (alreadyInActiveQueue(c, appointment.patientId)) throw new IllegalArgumentException("O paciente já está numa fila activa.");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue (appointment_id, patient_id, status, priority,added_at) VALUES (?, ?, 'AGUARDANDO', ?,?)")) {
                    ps.setInt(1, appointmentId); ps.setInt(2, appointment.patientId); ps.setInt(3, priority); ps.setString(4, nowAtHospital()); ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE appointments SET status='AGUARDANDO' WHERE id=?")) {
                    ps.setInt(1, appointmentId); ps.executeUpdate();
                }
                AuditLogger.log(c, null, "", "QUEUE_CHECK_IN", "APPOINTMENT", appointmentId, "priority=" + priority);
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao adicionar a consulta à fila.", e); }
    }

    @Override
    public QueueEntry addWalkInToQueue(int patientId, String doctor, String specialty, int priority) {
        validatePriority(priority);
        if (patientId <= 0 || doctor == null || doctor.isBlank() || specialty == null || specialty.isBlank())
            throw new IllegalArgumentException("Paciente, médico, especialidade e prioridade são obrigatórios.");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!patientExists(c, patientId)) throw new IllegalArgumentException("Paciente não encontrado.");
                if (alreadyInActiveQueue(c, patientId)) throw new IllegalArgumentException("O paciente já está numa fila activa.");
                String doctorName = null, specialtyName = null;
                try (PreparedStatement ps = c.prepareStatement("SELECT d.full_name,s.name FROM doctors d JOIN specialties s ON s.id=d.specialty_id WHERE d.active=1 AND s.active=1")) {
                    try (ResultSet r = ps.executeQuery()) {
                        while (r.next()) if (normalizeDoctorName(r.getString(1)).equals(normalizeDoctorName(doctor))
                                && normalizeDoctorName(r.getString(2)).equals(normalizeDoctorName(specialty))) {
                            doctorName = r.getString(1); specialtyName = r.getString(2); break;
                        }
                    }
                }
                if (doctorName == null) throw new IllegalArgumentException("Médico activo ou especialidade não encontrados.");
                java.time.LocalDateTime slot = java.time.LocalDateTime.now(hospitalZone()).withSecond(0).withNano(0);
                slot = slot.withMinute(slot.getMinute() < 30 ? 30 : 0);
                if (slot.getMinute() == 0) slot = slot.plusHours(1);
                String date = slot.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM appointments WHERE doctor=? AND appointment_date=? AND status NOT IN ('CANCELADA','FALTOU','DESISTIU')")) {
                    ps.setString(1, doctorName); ps.setString(2, date);
                    try (ResultSet r = ps.executeQuery()) { if (r.next()) throw new IllegalArgumentException("O médico já tem uma consulta neste horário; tente novamente mais tarde."); }
                }
                int appointmentId;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO appointments(patient_id,doctor,appointment_date,specialty,status) VALUES(?,?,?,?,'AGUARDANDO')", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, patientId); ps.setString(2, doctorName); ps.setString(3, date); ps.setString(4, specialtyName); ps.executeUpdate();
                    try (ResultSet k = ps.getGeneratedKeys()) { if (!k.next()) throw new RuntimeException("Não foi possível criar a consulta sem marcação."); appointmentId = k.getInt(1); }
                }
                int queueId;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue(appointment_id,patient_id,status,priority,added_at) VALUES(?,?,'AGUARDANDO',?,?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, appointmentId); ps.setInt(2, patientId); ps.setInt(3, priority); ps.setString(4, nowAtHospital()); ps.executeUpdate();
                    try (ResultSet k = ps.getGeneratedKeys()) { if (!k.next()) throw new RuntimeException("Não foi possível incluir na fila."); queueId = k.getInt(1); }
                }
                AuditLogger.log(c, null, "", "WALK_IN_CREATED", "APPOINTMENT", appointmentId, "priority=" + priority);
                c.commit();
                try (PreparedStatement ps = c.prepareStatement("SELECT q.id,q.appointment_id,q.patient_id,p.full_name,q.added_at,q.status,q.priority,a.doctor FROM queue q JOIN patients p ON p.id=q.patient_id JOIN appointments a ON a.id=q.appointment_id WHERE q.id=?")) {
                    ps.setInt(1, queueId); try (ResultSet r = ps.executeQuery()) { r.next(); return entry(r); }
                }
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao criar consulta sem marcação.", e); }
    }

    @Override
    public void withdrawQueueEntry(int queueEntryId, String reason) {
        String why = reason == null ? "" : reason.trim();
        if (queueEntryId <= 0 || why.isBlank()) throw new IllegalArgumentException("Indique o motivo da desistência.");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("UPDATE queue SET status='DESISTIU',withdrawal_reason=?,withdrawn_at=? WHERE id=? AND status='AGUARDANDO'")) {
                ps.setString(1, why); ps.setString(2, java.time.ZonedDateTime.now(hospitalZone()).toString()); ps.setInt(3, queueEntryId);
                if (ps.executeUpdate() != 1) throw new IllegalArgumentException("A entrada não existe ou já saiu da fila.");
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE appointments SET status='DESISTIU',cancel_reason=? WHERE id=(SELECT appointment_id FROM queue WHERE id=?) AND status='AGUARDANDO'")) {
                ps.setString(1, why); ps.setInt(2, queueEntryId); ps.executeUpdate();
            }
            AuditLogger.log(c, null, "", "QUEUE_WITHDRAWN", "QUEUE", queueEntryId, why);
            c.commit();
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao registar desistência.", e); }
    }

    private static void validatePriority(int priority) {
        if (priority < 0 || priority > 4) throw new IllegalArgumentException("Prioridade inválida (0 normal, 1 criança, 2 grávida, 3 idoso, 4 urgente).");
    }
    private static java.time.ZoneId hospitalZone() {
        try { return java.time.ZoneId.of(System.getenv().getOrDefault("HOSPITAL_TIMEZONE", "Africa/Maputo")); }
        catch (Exception e) { return java.time.ZoneId.of("Africa/Maputo"); }
    }
    private static String nowAtHospital() {
        return java.time.ZonedDateTime.now(hospitalZone()).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private boolean patientExists(Connection c, int id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM patients WHERE id=? LIMIT 1")) { ps.setInt(1,id); try(ResultSet r=ps.executeQuery()){return r.next();} }
    }
    private boolean alreadyInActiveQueue(Connection c, int patientId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM queue WHERE patient_id=? AND status IN ('AGUARDANDO','CHAMADA','EM_ATENDIMENTO') LIMIT 1")) { ps.setInt(1,patientId); try(ResultSet r=ps.executeQuery()){return r.next();} }
    }
    private AppointmentRow findAppointment(Connection c, int id) throws Exception {
        try (PreparedStatement ps=c.prepareStatement("SELECT id, patient_id, doctor, status,appointment_date FROM appointments WHERE id=?")) {
            ps.setInt(1,id); try(ResultSet r=ps.executeQuery()){ if(!r.next()) return null; return new AppointmentRow(r.getInt(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5)); }
        }
    }

    @Override public QueueEntry peekNextQueueEntry() { return findNextEntry(false); }
    @Override public QueueEntry getNextQueueEntry() { return findNextEntry(true); }

    @Override
    public QueueEntry peekNextQueueEntryForDoctor(String doctor) {
        return findNextEntryForDoctor(doctor, false);
    }

    @Override
    public QueueEntry getNextQueueEntryForDoctor(String doctor) {
        return findNextEntryForDoctor(doctor, true);
    }

    @Override
    public QueueEntry callNextQueueEntryForDoctor(String sessionToken, String doctor) {
        int actorId = SessionManager.requireDoctor(sessionToken, doctor);
        return findNextEntryForDoctor(doctor, true, true, actorId);
    }

    @Override
    public double estimatedWaitMinutesForQueueEntry(int queueEntryId) {
        if (queueEntryId <= 0) throw new IllegalArgumentException("ID da fila inválido.");
        try (Connection c = DatabaseManager.getConnection()) {
            String doctor; int priority; String addedAt;
            try (PreparedStatement ps = c.prepareStatement("SELECT a.doctor,q.priority,q.added_at FROM queue q JOIN appointments a ON a.id=q.appointment_id WHERE q.id=? AND q.status='AGUARDANDO'")) {
                ps.setInt(1, queueEntryId); try (ResultSet r = ps.executeQuery()) {
                    if (!r.next()) throw new IllegalArgumentException("A entrada já não está a aguardar.");
                    doctor = r.getString(1); priority = r.getInt(2); addedAt = r.getString(3);
                }
            }
            double averageMinutes = 30.0;
            try (PreparedStatement ps = c.prepareStatement("SELECT AVG((julianday(q.completed_at)-julianday(q.started_at))*1440.0) FROM queue q JOIN appointments a ON a.id=q.appointment_id WHERE a.doctor=? AND q.status='CONCLUIDA' AND q.started_at IS NOT NULL AND q.completed_at IS NOT NULL")) {
                ps.setString(1, doctor); try (ResultSet r = ps.executeQuery()) { if (r.next() && r.getDouble(1) > 0) averageMinutes = r.getDouble(1); }
            }
            int ahead;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM queue q JOIN appointments a ON a.id=q.appointment_id WHERE a.doctor=? AND q.status='AGUARDANDO' AND (q.priority>? OR (q.priority=? AND (q.added_at<? OR (q.added_at=? AND q.id<?))))")) {
                ps.setString(1, doctor); ps.setInt(2, priority); ps.setInt(3, priority); ps.setString(4, addedAt); ps.setString(5, addedAt); ps.setInt(6, queueEntryId);
                try (ResultSet r = ps.executeQuery()) { r.next(); ahead = r.getInt(1); }
            }
            int active = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM appointments WHERE doctor=? AND status='EM_ATENDIMENTO'")) {
                ps.setString(1, doctor); try (ResultSet r = ps.executeQuery()) { r.next(); active = r.getInt(1); }
            }
            return Math.round(averageMinutes * (ahead + active) * 100.0) / 100.0;
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao estimar tempo de espera.", e); }
    }

    private QueueEntry findNextEntry(boolean consume) {
        String select = "SELECT q.id,q.appointment_id,q.patient_id,p.full_name,q.added_at,q.status,q.priority,COALESCE(a.doctor,'') doctor FROM queue q JOIN patients p ON p.id=q.patient_id LEFT JOIN appointments a ON a.id=q.appointment_id WHERE q.status='AGUARDANDO' ORDER BY q.priority DESC,q.added_at,q.id LIMIT 1";
        try(Connection c=DatabaseManager.getConnection()){
            c.setAutoCommit(false);
            try(PreparedStatement ps=c.prepareStatement(select); ResultSet r=ps.executeQuery()){
                if(!r.next()){c.commit(); return new QueueEntry(0,0,0,"","","","VAZIA",0);}
                QueueEntry e=entry(r);
                if(consume){
                    try(PreparedStatement up=c.prepareStatement("UPDATE queue SET status='CHAMADA',called_at=? WHERE id=?")){up.setString(1,java.time.ZonedDateTime.now(hospitalZone()).toString());up.setInt(2,e.id);up.executeUpdate();}
                    if(e.appointmentId>0){ try(PreparedStatement up=c.prepareStatement("UPDATE appointments SET status='CHAMADA' WHERE id=?")){up.setInt(1,e.appointmentId);up.executeUpdate();} }
                }
                c.commit(); return e;
            } catch(Exception ex){c.rollback();throw ex;}
        }catch(Exception e){throw new RuntimeException("Erro ao consultar a fila.",e);}
    }


    private QueueEntry findNextEntryForDoctor(String doctor, boolean consume) {
        return findNextEntryForDoctor(doctor, consume, false);
    }

    private QueueEntry findNextEntryForDoctor(String doctor, boolean consume, boolean notifyReception) {
        return findNextEntryForDoctor(doctor, consume, notifyReception, 0);
    }

    private QueueEntry findNextEntryForDoctor(String doctor, boolean consume, boolean notifyReception, int actorId) {
        String wantedDoctor = normalizeDoctorName(doctor);
        if (wantedDoctor.isBlank()) {
            throw new IllegalArgumentException("O médico é obrigatório.");
        }

        String select = """
                SELECT q.id, q.appointment_id, q.patient_id, p.full_name,
                       q.added_at, q.status, q.priority, COALESCE(a.doctor, '') doctor
                FROM queue q
                JOIN patients p ON p.id = q.patient_id
                LEFT JOIN appointments a ON a.id = q.appointment_id
                WHERE q.status = 'AGUARDANDO'
                ORDER BY q.priority DESC, q.added_at, q.id
                """;

        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(select);
                 ResultSet r = ps.executeQuery()) {

                QueueEntry selected = null;
                while (r.next()) {
                    QueueEntry candidate = entry(r);
                    if (normalizeDoctorName(candidate.doctor).equals(wantedDoctor)) {
                        selected = candidate;
                        break;
                    }
                }

                if (selected == null) {
                    c.commit();
                    return new QueueEntry(0, 0, 0, "", "", "", "VAZIA", 0);
                }

                if (consume) {
                    if (selected.appointmentId > 0 && doctorHasActiveAppointment(c, selected.doctor, selected.appointmentId)) {
                        throw new IllegalStateException(
                                "O médico já está a atender outro paciente."
                        );
                    }

                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE queue SET status='CHAMADA',called_at=? WHERE id=? AND status='AGUARDANDO'")) {
                        up.setString(1, java.time.ZonedDateTime.now(hospitalZone()).toString());
                        up.setInt(2, selected.id);
                        if (up.executeUpdate() != 1) {
                            throw new IllegalStateException("O paciente já não está disponível na fila.");
                        }
                    }

                    if (selected.appointmentId > 0) {
                        try (PreparedStatement up = c.prepareStatement(
                                "UPDATE appointments SET status='CHAMADA' WHERE id=? AND status='AGUARDANDO'")) {
                            up.setInt(1, selected.appointmentId);
                            if (up.executeUpdate() != 1) {
                                throw new IllegalStateException(
                                        "A consulta já não está disponível para chamada."
                                );
                            }
                        }
                        if (notifyReception) {
                            String room = findDoctorRoom(c, selected.doctor);
                            try (PreparedStatement notice = c.prepareStatement("INSERT INTO notifications(appointment_id,patient_id,patient_name,doctor,created_at,is_read,clinic_room) VALUES(?,?,?,?,?,0,?)")) {
                                notice.setInt(1, selected.appointmentId); notice.setInt(2, selected.patientId);
                                notice.setString(3, selected.patientName); notice.setString(4, selected.doctor);
                                notice.setString(5, java.time.ZonedDateTime.now(hospitalZone()).toString()); notice.setString(6, room);
                                notice.executeUpdate();
                            }
                            mz.hospital.server.db.AuditLogger.log(c, actorId > 0 ? actorId : null, "", "PATIENT_CALLED", "APPOINTMENT", selected.appointmentId, "doctor=" + selected.doctor);
                        }
                    }

                    c.commit();
                    return new QueueEntry(
                            selected.id,
                            selected.appointmentId,
                            selected.patientId,
                            selected.patientName,
                            selected.doctor,
                            selected.addedAt,
                            "CHAMADA",
                            selected.priority
                    );
                }

                c.commit();
                return selected;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao consultar a fila do médico.",
                    e
            );
        }
    }

    private boolean doctorHasActiveAppointment(Connection connection, String doctor, int appointmentId) throws Exception {
        String wantedDoctor = normalizeDoctorName(doctor);
        if (wantedDoctor.isBlank()) return false;

        String sql = "SELECT id, doctor FROM appointments WHERE status='EM_ATENDIMENTO' AND id<>?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, appointmentId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    if (wantedDoctor.equals(normalizeDoctorName(r.getString("doctor")))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String findDoctorRoom(Connection c, String doctor) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT full_name,clinic_room FROM doctors")) {
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) if (normalizeDoctorName(r.getString(1)).equals(normalizeDoctorName(doctor))) return r.getString(2);
            }
        }
        return "";
    }

    private String normalizeDoctorName(String name) {
        String text = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        text = text.replaceAll("\\b(dr|dra|doutor|doutora)\\b\\.?", " ");
        return text.trim().replaceAll("\\s+", " ");
    }

    private QueueEntry entry(ResultSet r) throws Exception {
        return new QueueEntry(r.getInt("id"),r.getInt("appointment_id"),r.getInt("patient_id"),r.getString("full_name"),r.getString("doctor"),r.getString("added_at"),r.getString("status"),r.getInt("priority"));
    }

    @Override public QueueEntry[] listQueue() {
        List<QueueEntry> entries=new ArrayList<>();
        String sql="SELECT q.id,q.appointment_id,q.patient_id,p.full_name,q.added_at,q.status,q.priority,COALESCE(a.doctor,'') doctor FROM queue q JOIN patients p ON p.id=q.patient_id LEFT JOIN appointments a ON a.id=q.appointment_id WHERE q.status IN ('AGUARDANDO','CHAMADA','EM_ATENDIMENTO') ORDER BY q.priority DESC,q.added_at,q.id";
        try(Connection c=DatabaseManager.getConnection();PreparedStatement ps=c.prepareStatement(sql);ResultSet r=ps.executeQuery()){while(r.next())entries.add(entry(r));return entries.toArray(new QueueEntry[0]);}
        catch(Exception e){throw new RuntimeException("Erro ao listar a fila.",e);}
    }

    @Override public int peekNextPatient(){QueueEntry e=peekNextQueueEntry();return e.id==0?0:e.patientId;}
    @Override public int getNextPatient(){QueueEntry e=getNextQueueEntry();return e.id==0?0:e.patientId;}
    @Override public int getQueueSize(){try(Connection c=DatabaseManager.getConnection();PreparedStatement ps=c.prepareStatement("SELECT COUNT(*) FROM queue WHERE status IN ('AGUARDANDO','CHAMADA','EM_ATENDIMENTO')");ResultSet r=ps.executeQuery()){return r.next()?r.getInt(1):0;}catch(Exception e){throw new RuntimeException("Erro ao consultar tamanho da fila.",e);}}

    private record AppointmentRow(int id,int patientId,String doctor,String status,String appointmentDate) {}
}
