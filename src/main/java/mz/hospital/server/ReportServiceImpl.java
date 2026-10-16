package mz.hospital.server;

import Hospital.AverageTime;
import Hospital.DailySummaryData;
import Hospital.ReportServicePOA;
import Hospital.SpecialtyVolume;
import Hospital.ReceptionStatisticsData;
import mz.hospital.server.db.DatabaseManager;
import mz.hospital.server.security.SessionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ReportServiceImpl extends ReportServicePOA {
    @Override public DailySummaryData dailySummary(String token, String dateValue) {
        SessionManager.requireAdmin(token);
        LocalDate date = parseDate(dateValue);
        String day = date.toString();
        try (Connection c = DatabaseManager.getConnection()) {
            long registered = count(c, "SELECT COUNT(*) FROM appointments WHERE substr(appointment_date,1,10)=?", day);
            long waiting = count(c, "SELECT COUNT(*) FROM appointments WHERE substr(appointment_date,1,10)=? AND status IN ('AGUARDANDO','CHAMADA','EM_ATENDIMENTO')", day);
            long attended = count(c, "SELECT COUNT(*) FROM appointments WHERE substr(appointment_date,1,10)=? AND status='CONCLUIDA'", day);
            long absences = count(c, "SELECT COUNT(*) FROM appointments WHERE substr(appointment_date,1,10)=? AND status='FALTOU'", day);
            long cancellations = count(c, "SELECT COUNT(*) FROM appointments WHERE substr(appointment_date,1,10)=? AND status='CANCELADA'", day);
            return new DailySummaryData(Math.toIntExact(registered), Math.toIntExact(waiting), Math.toIntExact(attended), Math.toIntExact(absences), Math.toIntExact(cancellations));
        } catch (Exception e) { throw new RuntimeException("Erro ao gerar resumo diário.", e); }
    }

    @Override public AverageTime[] averageTimes(String token, String fromDate, String toDate) {
        SessionManager.requireAdmin(token);
        String from = parseDate(fromDate).toString(), to = parseDate(toDate).plusDays(1).toString();
        List<AverageTime> result = new ArrayList<>();
        String sql = """
                SELECT a.doctor, COUNT(*) completed,
                       AVG((julianday(q.called_at)-julianday(q.added_at))*1440.0) wait_minutes,
                       AVG((julianday(q.completed_at)-julianday(q.started_at))*1440.0) service_minutes
                FROM queue q JOIN appointments a ON a.id=q.appointment_id
                WHERE q.status='CONCLUIDA' AND q.called_at IS NOT NULL AND q.started_at IS NOT NULL
                  AND q.completed_at IS NOT NULL AND substr(q.added_at,1,10)>=? AND substr(q.added_at,1,10)<?
                GROUP BY a.doctor ORDER BY a.doctor
                """;
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, from); ps.setString(2, to);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) result.add(new AverageTime(r.getString(1), round(r.getDouble(3)), round(r.getDouble(4)), r.getInt(2)));
            }
            return result.toArray(new AverageTime[0]);
        } catch (Exception e) { throw new RuntimeException("Erro ao calcular tempos médios.", e); }
    }

    @Override public SpecialtyVolume[] specialtyVolumes(String token, String fromDate, String toDate) {
        SessionManager.requireAdmin(token);
        String from = parseDate(fromDate).toString(), to = parseDate(toDate).plusDays(1).toString();
        List<SpecialtyVolume> result = new ArrayList<>();
        String sql = "SELECT specialty,COUNT(*),SUM(CASE WHEN status='FALTOU' THEN 1 ELSE 0 END),SUM(CASE WHEN status='CANCELADA' THEN 1 ELSE 0 END) FROM appointments WHERE substr(appointment_date,1,10)>=? AND substr(appointment_date,1,10)<? GROUP BY specialty ORDER BY specialty";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, from); ps.setString(2, to);
            try (ResultSet r = ps.executeQuery()) { while (r.next()) result.add(new SpecialtyVolume(r.getString(1), r.getInt(2), r.getInt(3), r.getInt(4))); }
            return result.toArray(new SpecialtyVolume[0]);
        } catch (Exception e) { throw new RuntimeException("Erro ao gerar volume por especialidade.", e); }
    }

    @Override public ReceptionStatisticsData receptionStatistics(String token, String fromDate, String toDate) {
        SessionManager.requireReceptionOrAdmin(token);
        LocalDate from=parseDate(fromDate), to=parseDate(toDate);
        if(to.isBefore(from)) throw new IllegalArgumentException("O fim do período antecede o início.");
        String start=from.toString(), end=to.plusDays(1).toString();
        try(Connection c=DatabaseManager.getConnection()) {
            long appointments=countRange(c,"SELECT COUNT(*) FROM appointments WHERE substr(appointment_date,1,10)>=? AND substr(appointment_date,1,10)<?",start,end);
            long attended=countRange(c,"SELECT COUNT(*) FROM appointments WHERE status='CONCLUIDA' AND substr(appointment_date,1,10)>=? AND substr(appointment_date,1,10)<?",start,end);
            long registered=countRange(c,"SELECT COUNT(*) FROM patients WHERE substr(registered_at,1,10)>=? AND substr(registered_at,1,10)<?",start,end);
            long dropouts=countRange(c,"SELECT COUNT(*) FROM queue WHERE status='DESISTIU' AND substr(added_at,1,10)>=? AND substr(added_at,1,10)<?",start,end);
            long absences=countRange(c,"SELECT COUNT(*) FROM appointments WHERE status='FALTOU' AND substr(appointment_date,1,10)>=? AND substr(appointment_date,1,10)<?",start,end);
            double averageWait=0;
            try(PreparedStatement ps=c.prepareStatement("SELECT AVG((julianday(called_at)-julianday(added_at))*1440.0) FROM queue WHERE called_at IS NOT NULL AND substr(added_at,1,10)>=? AND substr(added_at,1,10)<?")){
                ps.setString(1,start);ps.setString(2,end);try(ResultSet r=ps.executeQuery()){if(r.next())averageWait=r.getDouble(1);}
            }
            double divisor=appointments==0?1.0:appointments;
            return new ReceptionStatisticsData((int)appointments,(int)attended,(int)registered,(int)dropouts,(int)absences,round(averageWait),round(dropouts*100.0/divisor),round(absences*100.0/divisor));
        }catch(Exception e){throw new RuntimeException("Erro ao calcular as estatísticas da recepção.",e);}
    }

    private static long count(Connection c, String sql, String day) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setString(1, day); try (ResultSet r = ps.executeQuery()) { return r.next() ? r.getLong(1) : 0; } }
    }
    private static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); } catch (Exception e) { throw new IllegalArgumentException("Use uma data no formato AAAA-MM-DD."); }
    }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private static long countRange(Connection c,String sql,String from,String to)throws Exception{try(PreparedStatement ps=c.prepareStatement(sql)){ps.setString(1,from);ps.setString(2,to);try(ResultSet r=ps.executeQuery()){return r.next()?r.getLong(1):0;}}}
}
