package mz.hospital.server;

import Hospital.AuthServicePOA;
import Hospital.User;
import mz.hospital.server.db.DatabaseManager;
import mz.hospital.server.security.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class AuthServiceImpl extends AuthServicePOA {

    @Override
    public User login(
            String username,
            String password
    ) {

        String sql = """
                SELECT id, username, password_hash, full_name, role
                FROM users
                WHERE username = ?
                """;

        try (Connection connection =
                     DatabaseManager.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, username);

            try (ResultSet result =
                         statement.executeQuery()) {

                if (!result.next()) {
                    throw new RuntimeException(
                            "Utilizador ou password inválidos."
                    );
                }

                String storedHash =
                        result.getString("password_hash");

                if (!PasswordHasher.verify(
                        password,
                        storedHash
                )) {
                    throw new RuntimeException(
                            "Utilizador ou password inválidos."
                    );
                }

                return new User(
                        result.getInt("id"),
                        result.getString("username"),
                        result.getString("full_name"),
                        result.getString("role")
                );
            }

        } catch (RuntimeException e) {
            throw e;

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao realizar autenticação.",
                    e
            );
        }
    }
}