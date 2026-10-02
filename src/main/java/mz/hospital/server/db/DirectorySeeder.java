package mz.hospital.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class DirectorySeeder {
    private DirectorySeeder() {}

    public static void seed() {
        String[] specialties = {"Clínica Geral", "Cardiologia", "Pediatria", "Medicina Dentária"};
        try (Connection c = DatabaseManager.getConnection()) {
            for (String name : specialties) {
                try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO specialties(name, active) VALUES(?,1)")) {
                    ps.setString(1, name); ps.executeUpdate();
                }
            }
            seedDoctor(c, "Dr. Carlos Silva", "Clínica Geral");
            seedDoctor(c, "Dra. Joana Paulo", "Cardiologia");
        } catch (Exception e) { throw new RuntimeException("Erro ao inicializar médicos e especialidades.", e); }
    }

    private static void seedDoctor(Connection c, String doctor, String specialty) throws Exception {
        String sql = "INSERT OR IGNORE INTO doctors(full_name, specialty_id, active) VALUES(?, (SELECT id FROM specialties WHERE name=?), 1)";
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setString(1, doctor); ps.setString(2, specialty); ps.executeUpdate(); }
    }
}
