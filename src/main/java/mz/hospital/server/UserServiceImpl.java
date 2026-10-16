package mz.hospital.server;

import Hospital.ManagedUser;
import Hospital.UserServicePOA;
import mz.hospital.server.db.AuditLogger;
import mz.hospital.server.db.DatabaseManager;
import mz.hospital.server.security.PasswordHasher;
import mz.hospital.server.security.SessionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class UserServiceImpl extends UserServicePOA {
    @Override public ManagedUser[] listUsers(String token) {
        SessionManager.requireAdmin(token);
        List<ManagedUser> users = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id,username,full_name,role,active FROM users ORDER BY full_name");
             ResultSet r = ps.executeQuery()) {
            while (r.next()) users.add(from(r));
            return users.toArray(new ManagedUser[0]);
        } catch (Exception e) { throw new RuntimeException("Erro ao listar utilizadores.", e); }
    }

    @Override public ManagedUser createUser(String token, String username, String fullName, String role, String initialPassword) {
        int actorId = SessionManager.requireAdmin(token);
        String user = clean(username), name = clean(fullName), normalizedRole = clean(role).toUpperCase();
        validatePassword(initialPassword);
        if (user.length() < 3 || name.isBlank()) throw new IllegalArgumentException("Indique um nome e um utilizador com pelo menos 3 caracteres.");
        if (!List.of("ADMIN", "RECEPTION", "DOCTOR").contains(normalizedRole)) throw new IllegalArgumentException("Perfil inválido.");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO users(username,password_hash,full_name,role,active) VALUES(?,?,?,?,1)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, user); ps.setString(2, PasswordHasher.hash(initialPassword)); ps.setString(3, name); ps.setString(4, normalizedRole); ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new RuntimeException("Não foi possível obter o ID do utilizador.");
                    int id = keys.getInt(1);
                    if ("DOCTOR".equals(normalizedRole)) mz.hospital.server.db.DoctorUserLinker.linkByName(c, name);
                    AuditLogger.log(c, actorId, "", "USER_CREATED", "USER", id, "Perfil=" + normalizedRole);
                    c.commit();
                    return new ManagedUser(id, user, name, normalizedRole, true);
                }
            } catch (Exception e) { c.rollback(); if (e instanceof java.sql.SQLException && e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) throw new IllegalArgumentException("Este nome de utilizador já existe."); throw e; }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao criar utilizador.", e); }
    }

    @Override public void setUserActive(String token, int id, boolean active) {
        int actorId = SessionManager.requireAdmin(token);
        if (id <= 0 || id == actorId && !active) throw new IllegalArgumentException("Não pode desactivar a sua própria conta.");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET active=?,failed_login_attempts=0,locked_until=NULL WHERE id=?")) {
                ps.setInt(1, active ? 1 : 0); ps.setInt(2, id);
                if (ps.executeUpdate() != 1) throw new IllegalArgumentException("Utilizador não encontrado.");
            }
            AuditLogger.log(c, actorId, "", active ? "USER_ACTIVATED" : "USER_DEACTIVATED", "USER", id, "");
            c.commit();
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao alterar estado do utilizador.", e); }
    }

    @Override public void resetPassword(String token, int id, String newPassword) {
        int actorId = SessionManager.requireAdmin(token); validatePassword(newPassword);
        updatePassword(id, newPassword, actorId, "ADMIN_PASSWORD_RESET");
    }

    @Override public void changePassword(String token, String currentPassword, String newPassword) {
        int userId = SessionManager.requireUser(token); validatePassword(newPassword);
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT password_hash FROM users WHERE id=? AND active=1")) {
            ps.setInt(1, userId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next() || !PasswordHasher.verify(currentPassword == null ? "" : currentPassword, r.getString(1)))
                    throw new IllegalArgumentException("A palavra-passe actual está incorrecta.");
            }
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao validar palavra-passe.", e); }
        updatePassword(userId, newPassword, userId, "PASSWORD_CHANGED");
    }

    private void updatePassword(int id, String password, int actorId, String action) {
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET password_hash=?,failed_login_attempts=0,locked_until=NULL WHERE id=?")) {
                ps.setString(1, PasswordHasher.hash(password)); ps.setInt(2, id);
                if (ps.executeUpdate() != 1) throw new IllegalArgumentException("Utilizador não encontrado.");
            }
            AuditLogger.log(c, actorId, "", action, "USER", id, ""); c.commit();
        } catch (IllegalArgumentException e) { throw e; }
          catch (Exception e) { throw new RuntimeException("Erro ao alterar palavra-passe.", e); }
    }

    private static ManagedUser from(ResultSet r) throws Exception {
        return new ManagedUser(r.getInt("id"), r.getString("username"), r.getString("full_name"), r.getString("role"), r.getInt("active") != 0);
    }
    private static String clean(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
    private static void validatePassword(String value) {
        if (value == null || value.length() < 10) throw new IllegalArgumentException("A palavra-passe deve ter pelo menos 10 caracteres.");
    }
}
