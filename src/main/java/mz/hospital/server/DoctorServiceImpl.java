package mz.hospital.server;

import Hospital.*;
import mz.hospital.server.db.AuditLogger;
import mz.hospital.server.db.DatabaseManager;
import mz.hospital.server.security.SessionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class DoctorServiceImpl extends DoctorServicePOA {
    @Override public Appointment[] myAppointments(String token) {
        SessionManager.requireUser(token);
        String doctor = ownDoctor(token);
        List<Appointment> items = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT a.id,a.patient_id,p.full_name,a.doctor,a.appointment_date,a.specialty,a.status FROM appointments a JOIN patients p ON p.id=a.patient_id WHERE a.doctor=? ORDER BY a.appointment_date,a.id")) {
            ps.setString(1, doctor);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) items.add(new Appointment(r.getInt(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)));
            }
            return items.toArray(new Appointment[0]);
        } catch (Exception e) { throw new RuntimeException("Erro ao listar as consultas do médico.", e); }
    }

    @Override public ClinicalNote[] patientHistory(String token, int patientId) {
        int actor = SessionManager.requireUser(token);
        String doctor = ownDoctor(token);
        if (patientId <= 0) throw new IllegalArgumentException("Paciente inválido.");
        String sql = """
                SELECT n.appointment_id,n.patient_id,p.full_name,n.doctor,a.appointment_date,
                       n.diagnosis,n.observations,n.prescription,n.created_at,n.vital_signs,n.care_plan,n.updated_at
                FROM clinical_notes n JOIN patients p ON p.id=n.patient_id
                JOIN appointments a ON a.id=n.appointment_id
                WHERE n.patient_id=? AND EXISTS (SELECT 1 FROM appointments own WHERE own.patient_id=n.patient_id AND own.doctor=? )
                ORDER BY a.appointment_date DESC,n.id DESC
                """;
        List<ClinicalNote> result = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, patientId); ps.setString(2, doctor);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) result.add(note(r));
            }
            AuditLogger.log(c, actor, "", "CLINICAL_HISTORY_VIEWED", "PATIENT", patientId, "");
            return result.toArray(new ClinicalNote[0]);
        } catch (Exception e) { throw new RuntimeException("Erro ao consultar o histórico clínico.", e); }
    }

    @Override public ClinicalNote saveClinicalNote(String token, int appointmentId, String diagnosis, String observations, String prescription) {
        return saveNote(token, appointmentId, diagnosis, observations, prescription, null, null);
    }

    @Override public ClinicalNote saveClinicalNoteExtended(String token, int appointmentId, String diagnosis, String observations, String prescription, String vitalSigns, String carePlan) {
        return saveNote(token, appointmentId, diagnosis, observations, prescription, vitalSigns, carePlan);
    }

    private ClinicalNote saveNote(String token, int appointmentId, String diagnosis, String observations, String prescription, String vitalSigns, String carePlan) {
        int actor = SessionManager.requireUser(token);
        String doctor = ownDoctor(token);
        String dx = clean(diagnosis), obs = clean(observations), rx = clean(prescription);
        String vitals = clean(vitalSigns), plan = clean(carePlan);
        if (appointmentId <= 0 || dx.isBlank()) throw new IllegalArgumentException("A consulta e o diagnostico sao obrigatorios.");
        if (dx.length() > 500 || obs.length() > 4000 || rx.length() > 2000 || vitals.length() > 1000 || plan.length() > 4000)
            throw new IllegalArgumentException("O registo clinico excede o tamanho permitido.");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                int patientId;
                String status, actualDoctor;
                try (PreparedStatement ps = c.prepareStatement("SELECT patient_id,doctor,status FROM appointments WHERE id=?")) {
                    ps.setInt(1, appointmentId);
                    try (ResultSet r = ps.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Consulta nao encontrada.");
                        patientId = r.getInt(1); actualDoctor = r.getString(2); status = r.getString(3);
                    }
                }
                if (!norm(actualDoctor).equals(norm(doctor))) throw new IllegalArgumentException("A consulta nao pertence ao medico autenticado.");
                if (!"EM_ATENDIMENTO".equals(status) && !"CONCLUIDA".equals(status)) throw new IllegalArgumentException("So e possivel registar notas durante ou apos o atendimento.");
                String now = ZonedDateTime.now(hospitalZone()).toString();
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO clinical_notes(appointment_id,patient_id,doctor,diagnosis,observations,prescription,vital_signs,care_plan,author_user_id,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(appointment_id) DO UPDATE SET diagnosis=excluded.diagnosis,observations=excluded.observations,
                        prescription=excluded.prescription,vital_signs=CASE WHEN ? THEN excluded.vital_signs ELSE clinical_notes.vital_signs END,
                        care_plan=CASE WHEN ? THEN excluded.care_plan ELSE clinical_notes.care_plan END,
                        author_user_id=excluded.author_user_id,updated_at=excluded.updated_at
                        """)) {
                    ps.setInt(1,appointmentId); ps.setInt(2,patientId); ps.setString(3,actualDoctor); ps.setString(4,dx);
                    ps.setString(5,obs); ps.setString(6,rx); ps.setString(7,vitals == null ? "" : vitals); ps.setString(8,plan == null ? "" : plan); ps.setInt(9,actor);
                    ps.setString(10,now); ps.setString(11,now); ps.setBoolean(12,vitalSigns != null); ps.setBoolean(13,carePlan != null); ps.executeUpdate();
                }
                AuditLogger.log(c, actor, "", "CLINICAL_NOTE_SAVED", "APPOINTMENT", appointmentId, "Clinical record updated");
                c.commit();
                return fetchNote(c, appointmentId);
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Error saving clinical record.", e); }
    }

    @Override public void updateMyAppointmentStatus(String token, int appointmentId, String newStatus) {
        int actor = SessionManager.requireUser(token);
        String doctor = ownDoctor(token);
        if (appointmentId <= 0) throw new IllegalArgumentException("Consulta inválida.");
        String expected, queueExpected;
        if ("EM_ATENDIMENTO".equals(newStatus)) { expected = "CHAMADA"; queueExpected = "CHAMADA"; }
        else if ("CONCLUIDA".equals(newStatus)) { expected = "EM_ATENDIMENTO"; queueExpected = "EM_ATENDIMENTO"; }
        else throw new IllegalArgumentException("O médico só pode iniciar ou concluir um atendimento.");
        String next = newStatus;
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                if ("CONCLUIDA".equals(next)) {
                    try (PreparedStatement note = c.prepareStatement("SELECT 1 FROM clinical_notes WHERE appointment_id=?")) {
                        note.setInt(1, appointmentId);
                        try (ResultSet result = note.executeQuery()) {
                            if (!result.next()) throw new IllegalArgumentException("Guarde o registo clínico antes de concluir a consulta.");
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE appointments SET status=? WHERE id=? AND doctor=? AND status=?")) {
                    ps.setString(1,next); ps.setInt(2,appointmentId); ps.setString(3,doctor); ps.setString(4,expected);
                    if (ps.executeUpdate()!=1) throw new IllegalArgumentException("Consulta indisponível ou já alterada.");
                }
                String column = "EM_ATENDIMENTO".equals(next) ? "started_at" : "completed_at";
                String now = ZonedDateTime.now(hospitalZone()).toString();
                try (PreparedStatement ps = c.prepareStatement("UPDATE queue SET status=?,"+column+"=? WHERE appointment_id=? AND status=?")) {
                    ps.setString(1,next); ps.setString(2,now); ps.setInt(3,appointmentId); ps.setString(4,queueExpected);
                    if (ps.executeUpdate()!=1) throw new IllegalArgumentException("A fila não está no estado esperado para esta consulta.");
                }
                AuditLogger.log(c,actor,"","DOCTOR_APPOINTMENT_STATE_CHANGED","APPOINTMENT",appointmentId,expected+" -> "+next);
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao atualizar o atendimento.",e); }
    }

    @Override public DoctorStatistics myStatistics(String token, String fromDate, String toDate) {
        String doctor = ownDoctor(token);
        LocalDate from = parse(fromDate), to = parse(toDate);
        if (to.isBefore(from)) throw new IllegalArgumentException("O fim do período antecede o início.");
        LocalDate today = LocalDate.now(hospitalZone());
        String month = today.withDayOfMonth(1).toString();
        try (Connection c = DatabaseManager.getConnection()) {
            long todayCount = count(c,"SELECT COUNT(*) FROM appointments WHERE doctor=? AND status='CONCLUIDA' AND substr(appointment_date,1,10)=?",doctor,today.toString());
            long monthCount = count(c,"SELECT COUNT(*) FROM appointments WHERE doctor=? AND status='CONCLUIDA' AND substr(appointment_date,1,10)>=? AND substr(appointment_date,1,10)<=?",doctor,month,today.toString());
            double average = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT AVG((julianday(completed_at)-julianday(started_at))*1440.0) FROM queue WHERE status='CONCLUIDA' AND appointment_id IN (SELECT id FROM appointments WHERE doctor=? AND substr(appointment_date,1,10)>=? AND substr(appointment_date,1,10)<=?) AND started_at IS NOT NULL AND completed_at IS NOT NULL")) {
                ps.setString(1,doctor); ps.setString(2,from.toString()); ps.setString(3,to.toString()); try(ResultSet r=ps.executeQuery()){if(r.next())average=r.getDouble(1);}
            }
            return new DoctorStatistics((int)todayCount,(int)monthCount,round(average));
        } catch (Exception e) { throw new RuntimeException("Erro ao calcular as estatísticas do médico.",e); }
    }

    @Override public DiagnosisVolume[] diagnosisVolumes(String token, String fromDate, String toDate) {
        String doctor = ownDoctor(token); LocalDate from=parse(fromDate), to=parse(toDate);
        if(to.isBefore(from))throw new IllegalArgumentException("O fim do período antecede o início.");
        List<DiagnosisVolume> result=new ArrayList<>();
        try(Connection c=DatabaseManager.getConnection();PreparedStatement ps=c.prepareStatement("SELECT n.diagnosis,COUNT(*) FROM clinical_notes n JOIN appointments a ON a.id=n.appointment_id WHERE n.doctor=? AND substr(a.appointment_date,1,10)>=? AND substr(a.appointment_date,1,10)<=? GROUP BY n.diagnosis ORDER BY COUNT(*) DESC,n.diagnosis")){
            ps.setString(1,doctor);ps.setString(2,from.toString());ps.setString(3,to.toString());try(ResultSet r=ps.executeQuery()){while(r.next())result.add(new DiagnosisVolume(r.getString(1),r.getInt(2)));}
            return result.toArray(new DiagnosisVolume[0]);
        }catch(Exception e){throw new RuntimeException("Erro ao agrupar diagnósticos.",e);}
    }

    private String ownDoctor(String token) {
        int id=SessionManager.requireUser(token); String fullName;
        try(Connection c=DatabaseManager.getConnection();PreparedStatement ps=c.prepareStatement("SELECT full_name,role FROM users WHERE id=? AND active=1")){
            ps.setInt(1,id);try(ResultSet r=ps.executeQuery()){if(!r.next()||!"DOCTOR".equals(r.getString(2)))throw new IllegalArgumentException("Operação disponível apenas para médicos.");fullName=r.getString(1);}
            try(PreparedStatement ps2=c.prepareStatement("SELECT full_name FROM doctors WHERE active=1" );ResultSet r=ps2.executeQuery()){
                while(r.next())if(norm(r.getString(1)).equals(norm(fullName))){
                    SessionManager.requireDoctor(token, r.getString(1));
                    return r.getString(1);
                }
            }
            throw new IllegalArgumentException("Não existe um médico associado à conta autenticada.");
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new RuntimeException("Não foi possível validar o médico.",e);}
    }
    private static ClinicalNote fetchNote(Connection c,int id)throws Exception{try(PreparedStatement ps=c.prepareStatement("SELECT n.appointment_id,n.patient_id,p.full_name,n.doctor,a.appointment_date,n.diagnosis,n.observations,n.prescription,n.created_at,n.vital_signs,n.care_plan,n.updated_at FROM clinical_notes n JOIN patients p ON p.id=n.patient_id JOIN appointments a ON a.id=n.appointment_id WHERE n.appointment_id=?")){ps.setInt(1,id);try(ResultSet r=ps.executeQuery()){if(!r.next())throw new IllegalArgumentException("Nota clínica não encontrada.");return note(r);}}}
    private static ClinicalNote note(ResultSet r)throws Exception{return new ClinicalNote(r.getInt(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12));}
    private static long count(Connection c,String sql,String...values)throws Exception{try(PreparedStatement ps=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)ps.setString(i+1,values[i]);try(ResultSet r=ps.executeQuery()){return r.next()?r.getLong(1):0;}}}
    private static LocalDate parse(String s){try{return LocalDate.parse(s);}catch(Exception e){throw new IllegalArgumentException("Use datas no formato AAAA-MM-DD.");}}
    private static String clean(String s){return s==null?"":s.trim();}
    private static String norm(String s){return java.text.Normalizer.normalize(s==null?"":s,java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}","").toLowerCase().replaceAll("\\b(dr|dra|doutor|doutora)\\b\\.?"," ").trim().replaceAll("\\s+"," ");}
    private static ZoneId hospitalZone(){try{return ZoneId.of(System.getenv().getOrDefault("HOSPITAL_TIMEZONE","Africa/Maputo"));}catch(Exception e){return ZoneId.of("Africa/Maputo");}}
    private static double round(double v){return Math.round(v*100.0)/100.0;}
}
