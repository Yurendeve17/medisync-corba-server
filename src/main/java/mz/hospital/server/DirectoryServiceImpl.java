package mz.hospital.server;

import Hospital.DirectoryServicePOA;
import Hospital.Doctor;
import Hospital.Specialty;
import mz.hospital.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DirectoryServiceImpl extends DirectoryServicePOA {

    @Override public Doctor[] listDoctors() { return listDoctors(false); }
    @Override public Specialty[] listSpecialties() { return listSpecialties(false); }
    @Override public Doctor[] listAllDoctors() { return listDoctors(true); }
    @Override public Specialty[] listAllSpecialties() { return listSpecialties(true); }

    private Doctor[] listDoctors(boolean includeInactive) {
        List<Doctor> list = new ArrayList<>();
        String sql = "SELECT d.id,d.full_name,s.name,d.active FROM doctors d JOIN specialties s ON s.id=d.specialty_id "
                + (includeInactive ? "" : "WHERE d.active=1 AND s.active=1 ") + "ORDER BY d.full_name";
        try (Connection c=DatabaseManager.getConnection(); PreparedStatement ps=c.prepareStatement(sql); ResultSet r=ps.executeQuery()) {
            while(r.next()) list.add(new Doctor(r.getInt(1),r.getString(2),r.getString(3),r.getInt(4)!=0));
            return list.toArray(new Doctor[0]);
        } catch(Exception e) { throw new RuntimeException("Erro ao listar médicos.",e); }
    }

    private Specialty[] listSpecialties(boolean includeInactive) {
        List<Specialty> list = new ArrayList<>();
        String sql="SELECT id,name,active FROM specialties " + (includeInactive ? "" : "WHERE active=1 ") + "ORDER BY name";
        try(Connection c=DatabaseManager.getConnection(); PreparedStatement ps=c.prepareStatement(sql); ResultSet r=ps.executeQuery()) {
            while(r.next()) list.add(new Specialty(r.getInt(1),r.getString(2),r.getInt(3)!=0));
            return list.toArray(new Specialty[0]);
        } catch(Exception e) { throw new RuntimeException("Erro ao listar especialidades.",e); }
    }

    @Override public Doctor createDoctor(String fullName, int specialtyId) {
        String name = cleanName(fullName);
        validateName(name, "O nome do médico é obrigatório.");
        try(Connection c=DatabaseManager.getConnection()) {
            ensureSpecialtyExists(c, specialtyId, true);
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO doctors(full_name,specialty_id,active) VALUES(?,?,1)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1,name); ps.setInt(2,specialtyId); ps.executeUpdate();
                try(ResultSet keys=ps.getGeneratedKeys()) { if(keys.next()) return findDoctor(c, keys.getInt(1)); }
            }
            throw new RuntimeException("Não foi possível obter o ID do médico.");
        } catch(java.sql.SQLException e) {
            if(e.getMessage()!=null && e.getMessage().toLowerCase().contains("unique")) throw new IllegalArgumentException("Já existe um médico com esse nome.");
            throw new RuntimeException("Erro ao cadastrar médico.",e);
        } catch(RuntimeException e){ throw e; } catch(Exception e){ throw new RuntimeException("Erro ao cadastrar médico.",e); }
    }

    @Override public Doctor updateDoctor(int id, String fullName, int specialtyId) {
        String name=cleanName(fullName); validateName(name,"O nome do médico é obrigatório.");
        try(Connection c=DatabaseManager.getConnection()) {
            Doctor current=findDoctor(c,id);
            if(current==null) throw new IllegalArgumentException("Médico não encontrado.");
            ensureSpecialtyExists(c,specialtyId,true);
            c.setAutoCommit(false);
            try(PreparedStatement ps=c.prepareStatement("UPDATE doctors SET full_name=?, specialty_id=? WHERE id=?")){ ps.setString(1,name);ps.setInt(2,specialtyId);ps.setInt(3,id);ps.executeUpdate(); }
            if(!current.fullName.equals(name)) {
                try(PreparedStatement ps=c.prepareStatement("UPDATE appointments SET doctor=? WHERE doctor=?")){ ps.setString(1,name);ps.setString(2,current.fullName);ps.executeUpdate(); }
            }
            c.commit(); return findDoctor(c,id);
        } catch(java.sql.SQLException e){ throw new RuntimeException("Erro ao atualizar médico.",e); }
    }

    @Override public void setDoctorActive(int id, boolean active) {
        try(Connection c=DatabaseManager.getConnection(); PreparedStatement ps=c.prepareStatement("UPDATE doctors SET active=? WHERE id=?")){
            ps.setInt(1,active?1:0);ps.setInt(2,id);
            if(ps.executeUpdate()==0) throw new IllegalArgumentException("Médico não encontrado.");
        } catch(RuntimeException e){throw e;} catch(Exception e){throw new RuntimeException("Erro ao alterar estado do médico.",e);}
    }

    @Override public Specialty createSpecialty(String name) {
        String value=cleanName(name); validateName(value,"O nome da especialidade é obrigatório.");
        try(Connection c=DatabaseManager.getConnection(); PreparedStatement ps=c.prepareStatement("INSERT INTO specialties(name,active) VALUES(?,1)",Statement.RETURN_GENERATED_KEYS)){
            ps.setString(1,value);ps.executeUpdate();
            try(ResultSet keys=ps.getGeneratedKeys()){if(keys.next())return findSpecialty(c,keys.getInt(1));}
            throw new RuntimeException("Não foi possível obter o ID da especialidade.");
        } catch(java.sql.SQLException e){if(e.getMessage()!=null&&e.getMessage().toLowerCase().contains("unique"))throw new IllegalArgumentException("Já existe uma especialidade com esse nome.");throw new RuntimeException("Erro ao cadastrar especialidade.",e);}
    }

    @Override public Specialty updateSpecialty(int id, String name) {
        String value=cleanName(name); validateName(value,"O nome da especialidade é obrigatório.");
        try(Connection c=DatabaseManager.getConnection()){
            Specialty current=findSpecialty(c,id); if(current==null)throw new IllegalArgumentException("Especialidade não encontrada.");
            try(PreparedStatement ps=c.prepareStatement("UPDATE specialties SET name=? WHERE id=?")){ps.setString(1,value);ps.setInt(2,id);ps.executeUpdate();}
            return findSpecialty(c,id);
        } catch(java.sql.SQLException e){if(e.getMessage()!=null&&e.getMessage().toLowerCase().contains("unique"))throw new IllegalArgumentException("Já existe uma especialidade com esse nome.");throw new RuntimeException("Erro ao atualizar especialidade.",e);}
    }

    @Override public void setSpecialtyActive(int id, boolean active) {
        try(Connection c=DatabaseManager.getConnection()){
            if(!active){try(PreparedStatement ps=c.prepareStatement("SELECT COUNT(*) FROM doctors WHERE specialty_id=? AND active=1")){ps.setInt(1,id);try(ResultSet r=ps.executeQuery()){if(r.next()&&r.getInt(1)>0)throw new IllegalArgumentException("Não é possível desativar uma especialidade que possui médicos ativos.");}}}
            try(PreparedStatement ps=c.prepareStatement("UPDATE specialties SET active=? WHERE id=?")){ps.setInt(1,active?1:0);ps.setInt(2,id);if(ps.executeUpdate()==0)throw new IllegalArgumentException("Especialidade não encontrada.");}
        } catch(RuntimeException e){throw e;} catch(Exception e){throw new RuntimeException("Erro ao alterar estado da especialidade.",e);}
    }

    private static String cleanName(String value){return value==null?"":value.trim().replaceAll("\\s+"," ");}
    private static void validateName(String value,String message){if(value.isBlank())throw new IllegalArgumentException(message);}

    private static void ensureSpecialtyExists(Connection c, int id, boolean active) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT active FROM specialties WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Especialidade não encontrada.");
                if (active && r.getInt(1) == 0) throw new IllegalArgumentException("A especialidade está desativada.");
            }
        }
    }

    private static Doctor findDoctor(Connection c, int id) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT d.id,d.full_name,s.name,d.active FROM doctors d JOIN specialties s ON s.id=d.specialty_id WHERE d.id=?")) {
            ps.setInt(1, id);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? new Doctor(r.getInt(1),r.getString(2),r.getString(3),r.getInt(4)!=0) : null;
            }
        }
    }

    private static Specialty findSpecialty(Connection c, int id) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id,name,active FROM specialties WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? new Specialty(r.getInt(1),r.getString(2),r.getInt(3)!=0) : null;
            }
        }
    }
}
