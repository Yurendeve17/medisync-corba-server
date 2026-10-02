package mz.hospital.server;

import Hospital.QueueEntry;
import Hospital.QueueServicePOA;
import mz.hospital.server.db.DatabaseManager;

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
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue (appointment_id, patient_id, status) VALUES (NULL, ?, 'AGUARDANDO')")) {
                ps.setInt(1, patientId); ps.executeUpdate();
            }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao adicionar paciente à fila.", e); }
    }

    @Override
    public void addAppointmentToQueue(int appointmentId) {
        if (appointmentId <= 0) throw new IllegalArgumentException("O ID da consulta é inválido.");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                AppointmentRow appointment = findAppointment(c, appointmentId);
                if (appointment == null) throw new IllegalArgumentException("Consulta não encontrada: #" + appointmentId);
                if (!AppointmentServiceImpl.CONFIRMADA.equals(appointment.status)) {
                    throw new IllegalArgumentException(
                            "Só é possível adicionar à fila uma consulta CONFIRMADA após a presença do paciente."
                    );
                }
                if (alreadyInActiveQueue(c, appointment.patientId)) throw new IllegalArgumentException("O paciente já está numa fila activa.");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue (appointment_id, patient_id, status) VALUES (?, ?, 'AGUARDANDO')")) {
                    ps.setInt(1, appointmentId); ps.setInt(2, appointment.patientId); ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE appointments SET status='AGUARDANDO' WHERE id=?")) {
                    ps.setInt(1, appointmentId); ps.executeUpdate();
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao adicionar a consulta à fila.", e); }
    }

    private boolean patientExists(Connection c, int id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM patients WHERE id=? LIMIT 1")) { ps.setInt(1,id); try(ResultSet r=ps.executeQuery()){return r.next();} }
    }
    private boolean alreadyInActiveQueue(Connection c, int patientId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM queue WHERE patient_id=? AND status IN ('AGUARDANDO','CHAMADA','EM_ATENDIMENTO') LIMIT 1")) { ps.setInt(1,patientId); try(ResultSet r=ps.executeQuery()){return r.next();} }
    }
    private AppointmentRow findAppointment(Connection c, int id) throws Exception {
        try (PreparedStatement ps=c.prepareStatement("SELECT id, patient_id, doctor, status FROM appointments WHERE id=?")) {
            ps.setInt(1,id); try(ResultSet r=ps.executeQuery()){ if(!r.next()) return null; return new AppointmentRow(r.getInt(1),r.getInt(2),r.getString(3),r.getString(4)); }
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

    private QueueEntry findNextEntry(boolean consume) {
        String select = "SELECT q.id,q.appointment_id,q.patient_id,p.full_name,q.added_at,q.status,COALESCE(a.doctor,'') doctor FROM queue q JOIN patients p ON p.id=q.patient_id LEFT JOIN appointments a ON a.id=q.appointment_id WHERE q.status='AGUARDANDO' ORDER BY q.id LIMIT 1";
        try(Connection c=DatabaseManager.getConnection()){
            c.setAutoCommit(false);
            try(PreparedStatement ps=c.prepareStatement(select); ResultSet r=ps.executeQuery()){
                if(!r.next()){c.commit(); return new QueueEntry(0,0,0,"","","","VAZIA");}
                QueueEntry e=entry(r);
                if(consume){
                    try(PreparedStatement up=c.prepareStatement("UPDATE queue SET status='CHAMADA' WHERE id=?")){up.setInt(1,e.id);up.executeUpdate();}
                    if(e.appointmentId>0){ try(PreparedStatement up=c.prepareStatement("UPDATE appointments SET status='CHAMADA' WHERE id=?")){up.setInt(1,e.appointmentId);up.executeUpdate();} }
                }
                c.commit(); return e;
            } catch(Exception ex){c.rollback();throw ex;}
        }catch(Exception e){throw new RuntimeException("Erro ao consultar a fila.",e);}
    }


    private QueueEntry findNextEntryForDoctor(String doctor, boolean consume) {
        String wantedDoctor = normalizeDoctorName(doctor);
        if (wantedDoctor.isBlank()) {
            throw new IllegalArgumentException("O médico é obrigatório.");
        }

        String select = """
                SELECT q.id, q.appointment_id, q.patient_id, p.full_name,
                       q.added_at, q.status, COALESCE(a.doctor, '') doctor
                FROM queue q
                JOIN patients p ON p.id = q.patient_id
                LEFT JOIN appointments a ON a.id = q.appointment_id
                WHERE q.status = 'AGUARDANDO'
                ORDER BY q.id
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
                    return new QueueEntry(0, 0, 0, "", "", "", "VAZIA");
                }

                if (consume) {
                    if (selected.appointmentId > 0 && doctorHasActiveAppointment(c, selected.doctor, selected.appointmentId)) {
                        throw new IllegalStateException(
                                "O médico já está a atender outro paciente."
                        );
                    }

                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE queue SET status='CHAMADA' WHERE id=? AND status='AGUARDANDO'")) {
                        up.setInt(1, selected.id);
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
                    }

                    c.commit();
                    return new QueueEntry(
                            selected.id,
                            selected.appointmentId,
                            selected.patientId,
                            selected.patientName,
                            selected.doctor,
                            selected.addedAt,
                            "CHAMADA"
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

    private String normalizeDoctorName(String name) {
        String text = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        text = text.replaceAll("\\b(dr|dra|doutor|doutora)\\b\\.?", " ");
        return text.trim().replaceAll("\\s+", " ");
    }

    private QueueEntry entry(ResultSet r) throws Exception {
        return new QueueEntry(r.getInt("id"),r.getInt("appointment_id"),r.getInt("patient_id"),r.getString("full_name"),r.getString("doctor"),r.getString("added_at"),r.getString("status"));
    }

    @Override public QueueEntry[] listQueue() {
        List<QueueEntry> entries=new ArrayList<>();
        String sql="SELECT q.id,q.appointment_id,q.patient_id,p.full_name,q.added_at,q.status,COALESCE(a.doctor,'') doctor FROM queue q JOIN patients p ON p.id=q.patient_id LEFT JOIN appointments a ON a.id=q.appointment_id WHERE q.status IN ('AGUARDANDO','CHAMADA','EM_ATENDIMENTO') ORDER BY q.id";
        try(Connection c=DatabaseManager.getConnection();PreparedStatement ps=c.prepareStatement(sql);ResultSet r=ps.executeQuery()){while(r.next())entries.add(entry(r));return entries.toArray(new QueueEntry[0]);}
        catch(Exception e){throw new RuntimeException("Erro ao listar a fila.",e);}
    }

    @Override public int peekNextPatient(){QueueEntry e=peekNextQueueEntry();return e.id==0?0:e.patientId;}
    @Override public int getNextPatient(){QueueEntry e=getNextQueueEntry();return e.id==0?0:e.patientId;}
    @Override public int getQueueSize(){try(Connection c=DatabaseManager.getConnection();PreparedStatement ps=c.prepareStatement("SELECT COUNT(*) FROM queue WHERE status IN ('AGUARDANDO','CHAMADA','EM_ATENDIMENTO')");ResultSet r=ps.executeQuery()){return r.next()?r.getInt(1):0;}catch(Exception e){throw new RuntimeException("Erro ao consultar tamanho da fila.",e);}}

    private record AppointmentRow(int id,int patientId,String doctor,String status) {}
}
