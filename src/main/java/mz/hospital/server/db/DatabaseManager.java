package mz.hospital.server.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private static final String URL =
            "jdbc:sqlite:src/main/resources/database.db";

    public static Connection getConnection()
            throws SQLException {

        return DriverManager.getConnection(URL);
    }

    public static void initialize() {

        String patientSql = """
                CREATE TABLE IF NOT EXISTS patients (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    full_name TEXT NOT NULL,
                    birth_date TEXT NOT NULL,
                    gender TEXT NOT NULL,
                    phone TEXT NOT NULL
                )
                """;

        String appointmentSql = """
                CREATE TABLE IF NOT EXISTS appointments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    patient_id INTEGER NOT NULL,
                    doctor TEXT NOT NULL,
                    appointment_date TEXT NOT NULL,
                    specialty TEXT NOT NULL,
                    FOREIGN KEY (patient_id)
                        REFERENCES patients(id)
                )
                """;

        String queueSql = """
                CREATE TABLE IF NOT EXISTS queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    patient_id INTEGER NOT NULL,
                    added_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (patient_id)
                        REFERENCES patients(id)
                )
                """;

        String userSql = """
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    full_name TEXT NOT NULL,
                    role TEXT NOT NULL
                )
                """;

        String notificationSql = """
                CREATE TABLE IF NOT EXISTS notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    appointment_id INTEGER NOT NULL,
                    patient_id INTEGER NOT NULL,
                    patient_name TEXT NOT NULL,
                    doctor TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    is_read INTEGER NOT NULL DEFAULT 0
                )
                """;

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute(patientSql);
            statement.execute(appointmentSql);
            statement.execute(queueSql);
            statement.execute(userSql);
            statement.execute(notificationSql);

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Erro ao inicializar a base de dados.",
                    e
            );
        }

        UserSeeder.seed();

        System.out.println(
                "Base de dados inicializada."
        );
    }
}