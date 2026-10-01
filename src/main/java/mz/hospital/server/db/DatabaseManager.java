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

    private static void ensureAppointmentStatusColumn(Statement statement) throws SQLException {
        boolean exists = false;
        try (java.sql.ResultSet result = statement.executeQuery("PRAGMA table_info(appointments)")) {
            while (result.next()) {
                if ("status".equalsIgnoreCase(result.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            statement.executeUpdate("ALTER TABLE appointments ADD COLUMN status TEXT NOT NULL DEFAULT 'AGENDADA'");
        }
    }


    private static void ensureQueueColumns(Statement statement) throws SQLException {
        boolean appointmentExists = false;
        boolean statusExists = false;
        try (java.sql.ResultSet result = statement.executeQuery("PRAGMA table_info(queue)")) {
            while (result.next()) {
                String name = result.getString("name");
                if ("appointment_id".equalsIgnoreCase(name)) appointmentExists = true;
                if ("status".equalsIgnoreCase(name)) statusExists = true;
            }
        }
        if (!appointmentExists) statement.executeUpdate("ALTER TABLE queue ADD COLUMN appointment_id INTEGER");
        if (!statusExists) statement.executeUpdate("ALTER TABLE queue ADD COLUMN status TEXT NOT NULL DEFAULT 'AGUARDANDO'");
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
                    status TEXT NOT NULL DEFAULT 'AGENDADA',
                    FOREIGN KEY (patient_id)
                        REFERENCES patients(id)
                )
                """;

        String queueSql = """
                CREATE TABLE IF NOT EXISTS queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    appointment_id INTEGER,
                    patient_id INTEGER NOT NULL,
                    added_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    status TEXT NOT NULL DEFAULT 'AGUARDANDO',
                    FOREIGN KEY (appointment_id) REFERENCES appointments(id),
                    FOREIGN KEY (patient_id) REFERENCES patients(id)
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
            ensureAppointmentStatusColumn(statement);
            statement.execute(queueSql);
            statement.execute(userSql);
            statement.execute(notificationSql);

            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_appointments_doctor_date ON appointments(doctor, appointment_date)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_appointments_patient ON appointments(patient_id)");
            ensureQueueColumns(statement);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_queue_patient ON queue(patient_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_queue_appointment ON queue(appointment_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_queue_status_order ON queue(status, id)");

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