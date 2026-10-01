package mz.hospital.server;

import Hospital.QueueEntry;
import Hospital.QueueServicePOA;
import mz.hospital.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
                if (!("AGENDADA".equals(appointment.status) || "AGUARDANDO".equals(appointment.status))) {
                    throw new IllegalArgumentException("A consulta #" + appointmentId + " não pode entrar na fila no estado " + appointment.status + ".");
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
