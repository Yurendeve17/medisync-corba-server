package mz.hospital.server;

import Hospital.*;
import mz.hospital.server.db.AuditLogger;
import mz.hospital.server.db.DatabaseManager;
import mz.hospital.server.security.SessionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class AdminServiceImpl extends AdminServicePOA {
    @Override public AuditEntry[] listAuditLogs(String token,String fromDate,String toDate) {
        SessionManager.requireAdmin(token);
        LocalDate from=parse(fromDate),to=parse(toDate);if(to.isBefore(from))throw new IllegalArgumentException("Período inválido.");
        List<AuditEntry> result=new ArrayList<>();
        try(Connection c=DatabaseManager.getConnection();PreparedStatement ps=c.prepareStatement("SELECT id,username,action,entity_type,COALESCE(entity_id,0),details,created_at FROM audit_log WHERE substr(created_at,1,10)>=? AND substr(created_at,1,10)<=? ORDER BY id DESC LIMIT 1000")){
            ps.setString(1,from.toString());ps.setString(2,to.toString());try(ResultSet r=ps.executeQuery()){while(r.next())result.add(new AuditEntry(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getString(6),r.getString(7)));}
            return result.toArray(new AuditEntry[0]);
        }catch(Exception e){throw new RuntimeException("Erro ao consultar auditoria.",e);}
    }
    @Override public SystemSetting[] listSettings(String token) {
        SessionManager.requireAdmin(token);List<SystemSetting> result=new ArrayList<>();
        try(Connection c=DatabaseManager.getConnection();PreparedStatement ps=c.prepareStatement("SELECT key,value FROM system_settings ORDER BY key");ResultSet r=ps.executeQuery()){
            while(r.next())result.add(new SystemSetting(r.getString(1),r.getString(2)));return result.toArray(new SystemSetting[0]);
        }catch(Exception e){throw new RuntimeException("Erro ao consultar configurações.",e);}
    }
    @Override public void setSetting(String token,String key,String value) {
        int actor=SessionManager.requireAdmin(token);String k=key==null?"":key.trim();String v=value==null?"":value.trim();
        if(!k.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")||v.length()>1000)throw new IllegalArgumentException("Chave ou valor de configuração inválido.");
        try(Connection c=DatabaseManager.getConnection()){
            c.setAutoCommit(false);
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO system_settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){ps.setString(1,k);ps.setString(2,v);ps.executeUpdate();}
            AuditLogger.log(c,actor,"","SYSTEM_SETTING_CHANGED","SETTING",null,k);c.commit();
        }catch(Exception e){throw new RuntimeException("Erro ao guardar configuração.",e);}
    }
    private static LocalDate parse(String s){try{return LocalDate.parse(s);}catch(Exception e){throw new IllegalArgumentException("Use datas no formato AAAA-MM-DD.");}}
}
