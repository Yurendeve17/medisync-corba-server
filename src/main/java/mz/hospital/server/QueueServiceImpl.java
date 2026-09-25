package mz.hospital.server;

import Hospital.QueueServicePOA;
import mz.hospital.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class QueueServiceImpl extends QueueServicePOA {

    @Override
    public void addToQueue(int patientId) {

        String sql = """
                INSERT INTO queue (patient_id)
                VALUES (?)
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setInt(1, patientId);
            statement.executeUpdate();

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao adicionar paciente à fila.",
                    e
            );
        }
    }

    @Override
    public int peekNextPatient() {

        String sql = """
            SELECT patient_id
            FROM queue
            ORDER BY id
            LIMIT 1
            """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            if (result.next()) {
                return result.getInt("patient_id");
            }

            return 0;

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao consultar próximo paciente da fila.",
                    e
            );
        }
    }

    @Override
    public int getNextPatient() {

        String selectSql = """
                SELECT id, patient_id
                FROM queue
                ORDER BY id
                LIMIT 1
                """;

        String deleteSql = """
                DELETE FROM queue
                WHERE id = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement selectStatement =
                     connection.prepareStatement(selectSql)) {

            connection.setAutoCommit(false);

            try (ResultSet result = selectStatement.executeQuery()) {

                if (!result.next()) {
                    connection.commit();
                    return 0;
                }

                int queueId = result.getInt("id");
                int patientId = result.getInt("patient_id");

                try (PreparedStatement deleteStatement =
                             connection.prepareStatement(deleteSql)) {

                    deleteStatement.setInt(1, queueId);
                    deleteStatement.executeUpdate();
                }

                connection.commit();

                return patientId;
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao obter próximo paciente da fila.",
                    e
            );
        }
    }

    @Override
    public int getQueueSize() {

        String sql = """
                SELECT COUNT(*)
                FROM queue
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            if (result.next()) {
                return result.getInt(1);
            }

            return 0;

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao consultar tamanho da fila.",
                    e
            );
        }
    }
}