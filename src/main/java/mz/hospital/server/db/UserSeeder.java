package mz.hospital.server.db;

import mz.hospital.server.security.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class UserSeeder {

    private UserSeeder() {
    }

    public static void seed() {

        String adminUsername = System.getenv("MEDISYNC_ADMIN_USERNAME");
        String adminPassword = System.getenv("MEDISYNC_ADMIN_PASSWORD");
        if (adminUsername != null && !adminUsername.isBlank()
                && adminPassword != null && adminPassword.length() >= 10) {
            createUserIfNotExists(adminUsername.trim(), "Administrador MediSync", "ADMIN", adminPassword);
        } else {
            System.err.println("Conta ADMIN não criada: configure MEDISYNC_ADMIN_USERNAME e MEDISYNC_ADMIN_PASSWORD (mínimo 10 caracteres).");
        }

        // Não criar credenciais previsíveis em instalações novas. A conta
        // administrativa inicial só é criada a partir das variáveis de ambiente.
    }

    private static void createUserIfNotExists(
            String username,
            String fullName,
            String role,
            String password
    ) {

        String checkSql = """
                SELECT id
                FROM users
                WHERE username = ?
                """;

        String insertSql = """
                INSERT INTO users (
                    username,
                    password_hash,
                    full_name,
                    role
                )
                VALUES (?, ?, ?, ?)
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement check =
                     connection.prepareStatement(checkSql)) {

            check.setString(1, username);

            try (ResultSet result = check.executeQuery()) {

                if (result.next()) {
                    return;
                }
            }

            String passwordHash =
                    PasswordHasher.hash(password);

            try (PreparedStatement insert =
                         connection.prepareStatement(insertSql)) {

                insert.setString(1, username);
                insert.setString(2, passwordHash);
                insert.setString(3, fullName);
                insert.setString(4, role);

                insert.executeUpdate();

                System.out.println(
                        "Utilizador criado: " + username
                );
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao criar utilizador: " + username,
                    e
            );
        }
    }
}
