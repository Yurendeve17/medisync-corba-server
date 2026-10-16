package mz.hospital.server.security;

import mz.hospital.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private SessionManager() {}

    public static String create(int userId, String role) {
        String token = UUID.randomUUID().toString();
        SESSIONS.put(token, new Session(userId, role));
        return token;
    }

    public static int requireAdmin(String token) {
        Session session = SESSIONS.get(token == null ? "" : token);
        if (session == null || !"ADMIN".equalsIgnoreCase(session.role)) {
            throw new IllegalArgumentException("Sessão de administrador inválida ou expirada.");
        }
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT active FROM users WHERE id=?")) {
            ps.setInt(1, session.userId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next() || r.getInt(1) == 0) {
                    SESSIONS.remove(token);
                    throw new IllegalArgumentException("A conta foi desativada.");
                }
            }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Não foi possível validar a sessão.", e); }
        return session.userId;
    }

    public static int requireUser(String token) {
        Session session = SESSIONS.get(token == null ? "" : token);
        if (session == null) throw new IllegalArgumentException("Sessão inválida ou expirada.");
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT active FROM users WHERE id=?")) {
            ps.setInt(1, session.userId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next() || r.getInt(1) == 0) {
                    SESSIONS.remove(token);
                    throw new IllegalArgumentException("A conta foi desativada.");
                }
            }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Não foi possível validar a sessão.", e); }
        return session.userId;
    }

    public static int requireDoctor(String token, String requestedDoctor) {
        int userId = requireUser(token);
        String fullName;
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT full_name,role FROM users WHERE id=? AND active=1")) {
            ps.setInt(1, userId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next() || !"DOCTOR".equalsIgnoreCase(r.getString("role")))
                    throw new IllegalArgumentException("A operação está disponível apenas para médicos.");
                fullName = r.getString("full_name");
            }
            try (PreparedStatement doctor = c.prepareStatement("SELECT full_name,user_id FROM doctors WHERE active=1")) {
                boolean matched = false;
                try (ResultSet r = doctor.executeQuery()) {
                    while (r.next()) {
                        if (normalize(fullName).equals(normalize(requestedDoctor))
                                && normalize(r.getString("full_name")).equals(normalize(requestedDoctor))) {
                            int linkedUserId = r.getInt("user_id");
                            boolean linked = !r.wasNull();
                            if (linked && linkedUserId != userId) continue;
                            matched = true; break;
                        }
                    }
                }
                if (!matched) throw new IllegalArgumentException("O médico da sessão não corresponde à fila solicitada.");
            }
            return userId;
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Não foi possível validar o perfil médico.", e); }
    }

    public static int requireReceptionOrAdmin(String token) {
        int userId = requireUser(token);
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT role FROM users WHERE id=? AND active=1")) {
            ps.setInt(1, userId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next() || !("RECEPTION".equalsIgnoreCase(r.getString(1)) || "ADMIN".equalsIgnoreCase(r.getString(1))))
                    throw new IllegalArgumentException("A operação está disponível apenas para recepção ou administração.");
            }
            return userId;
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Não foi possível validar o perfil da sessão.", e); }
    }

    private static String normalize(String value) {
        String text = java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase();
        return text.replaceAll("\\b(dr|dra|doutor|doutora)\\b\\.?", " ").trim().replaceAll("\\s+", " ");
    }

    public static void close(String token) { if (token != null) SESSIONS.remove(token); }

    private record Session(int userId, String role) {}
}
