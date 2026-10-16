package mz.hospital.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.Normalizer;

public final class DoctorUserLinker {
    private DoctorUserLinker() {}

    public static void linkByName(Connection c, String doctorName) throws Exception {
        int doctorId;
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM doctors WHERE active=1")) {
            try (ResultSet r = ps.executeQuery()) {
                doctorId = -1;
                while (r.next()) {
                    int candidateId = r.getInt(1);
                    try (PreparedStatement nameQuery = c.prepareStatement("SELECT full_name FROM doctors WHERE id=?")) {
                        nameQuery.setInt(1, candidateId);
                        try (ResultSet name = nameQuery.executeQuery()) {
                            if (name.next() && normalize(doctorName).equals(normalize(name.getString(1)))) { doctorId = candidateId; break; }
                        }
                    }
                }
            }
        }
        if (doctorId < 0) return;
        try (PreparedStatement users = c.prepareStatement("SELECT id,full_name FROM users WHERE role='DOCTOR' AND active=1")) {
            try (ResultSet r = users.executeQuery()) {
                while (r.next()) if (normalize(doctorName).equals(normalize(r.getString(2)))) {
                    try (PreparedStatement update = c.prepareStatement("UPDATE doctors SET user_id=? WHERE id=?")) {
                        update.setInt(1, r.getInt(1)); update.setInt(2, doctorId); update.executeUpdate();
                    }
                    return;
                }
            }
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("\\b(dr|dra|doutor|doutora)\\b\\.?", " ").trim().replaceAll("\\s+", " ");
    }
}
