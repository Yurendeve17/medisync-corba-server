package mz.hospital.server;

import Hospital.AuthServicePOA;
import Hospital.User;
import mz.hospital.server.db.AuditLogger;
import mz.hospital.server.db.DatabaseManager;
import mz.hospital.server.security.PasswordHasher;
import mz.hospital.server.security.SessionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;

public class AuthServiceImpl extends AuthServicePOA {
    private static final int MAX_ATTEMPTS = 5;

    @Override
    public User login(String username, String password) {
        String name = username == null ? "" : username.trim();
        if (name.isBlank() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Utilizador ou palavra-passe inválidos.");
        }
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT id,username,password_hash,full_name,role,active,failed_login_attempts,locked_until FROM users WHERE username=?")) {
                ps.setString(1, name);
                try (ResultSet r = ps.executeQuery()) {
                    if (!r.next()) {
                        AuditLogger.log(c, null, name, "LOGIN_FAILED", "USER", null, "Utilizador inexistente");
                        c.commit();
                        throw invalidCredentials();
                    }
                    int id = r.getInt("id");
                    String role = r.getString("role");
                    boolean active = r.getInt("active") != 0;
                    String lock = r.getString("locked_until");
                    LocalDateTime now = LocalDateTime.now();
                    if (!active || (lock != null && !lock.isBlank() && LocalDateTime.parse(lock).isAfter(now))) {
                        AuditLogger.log(c, id, name, "LOGIN_BLOCKED", "USER", id, "Conta inactiva ou temporariamente bloqueada");
                        c.commit();
                        throw invalidCredentials();
                    }
                    if (!PasswordHasher.verify(password, r.getString("password_hash"))) {
                        int attempts = r.getInt("failed_login_attempts") + 1;
                        String lockedUntil = attempts >= MAX_ATTEMPTS ? now.plusMinutes(15).toString() : null;
                        try (PreparedStatement update = c.prepareStatement("UPDATE users SET failed_login_attempts=?,locked_until=? WHERE id=?")) {
                            update.setInt(1, attempts);
                            if (lockedUntil == null) update.setNull(2, java.sql.Types.VARCHAR); else update.setString(2, lockedUntil);
                            update.setInt(3, id); update.executeUpdate();
                        }
                        AuditLogger.log(c, id, name, "LOGIN_FAILED", "USER", id, "Tentativa " + attempts);
                        c.commit();
                        throw invalidCredentials();
                    }
                    try (PreparedStatement update = c.prepareStatement("UPDATE users SET failed_login_attempts=0,locked_until=NULL WHERE id=?")) {
                        update.setInt(1, id); update.executeUpdate();
                    }
                    AuditLogger.log(c, id, name, "LOGIN", "USER", id, "Autenticação bem-sucedida");
                    c.commit();
                    String token = SessionManager.create(id, role);
                    return new User(id, name, r.getString("full_name"), role, token);
                }
            } catch (RuntimeException e) { if (!c.getAutoCommit()) c.rollback(); throw e; }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao realizar autenticação.", e); }
    }

    @Override public void logout(String sessionToken) { SessionManager.close(sessionToken); }

    private IllegalArgumentException invalidCredentials() {
        return new IllegalArgumentException("Utilizador ou palavra-passe inválidos.");
    }
}
