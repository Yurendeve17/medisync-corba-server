package mz.hospital.server;

import Hospital.DirectoryServicePOA;
import Hospital.Doctor;
import Hospital.Specialty;
import mz.hospital.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class DirectoryServiceImpl extends DirectoryServicePOA {
    @Override public Doctor[] listDoctors() {
        List<Doctor> list = new ArrayList<>();
        String sql = "SELECT d.id,d.full_name,s.name,d.active FROM doctors d JOIN specialties s ON s.id=d.specialty_id WHERE d.active=1 AND s.active=1 ORDER BY d.full_name";
        try (Connection c=DatabaseManager.getConnection(); PreparedStatement ps=c.prepareStatement(sql); ResultSet r=ps.executeQuery()) {
            while(r.next()) list.add(new Doctor(r.getInt(1),r.getString(2),r.getString(3),r.getInt(4)!=0));
            return list.toArray(new Doctor[0]);
        } catch(Exception e) { throw new RuntimeException("Erro ao listar médicos.",e); }
    }
    @Override public Specialty[] listSpecialties() {
        List<Specialty> list = new ArrayList<>();
        String sql="SELECT id,name,active FROM specialties WHERE active=1 ORDER BY name";
        try(Connection c=DatabaseManager.getConnection(); PreparedStatement ps=c.prepareStatement(sql); ResultSet r=ps.executeQuery()) {
            while(r.next()) list.add(new Specialty(r.getInt(1),r.getString(2),r.getInt(3)!=0));
            return list.toArray(new Specialty[0]);
        } catch(Exception e) { throw new RuntimeException("Erro ao listar especialidades.",e); }
    }
}
