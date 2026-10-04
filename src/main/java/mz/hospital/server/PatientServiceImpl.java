package mz.hospital.server;

import Hospital.Patient;
import Hospital.PatientServicePOA;
import mz.hospital.server.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class PatientServiceImpl extends PatientServicePOA {

    @Override
    public Patient registerPatient(
            String fullName,
            String birthDate,
            String gender,
            String phone,
            String address,
            String neighborhood,
            String city
    ) {
        String name = clean(fullName);
        String birth = clean(birthDate);
        String sex = clean(gender);
        String contact = clean(phone);
        String addr = clean(address);
        String bairro = clean(neighborhood);
        String localidade = clean(city);
        validate(name, birth, sex, contact, addr, bairro, localidade);

        String sql = """
                INSERT INTO patients
                (full_name, birth_date, gender, phone, address, neighborhood, city)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     sql,
                     java.sql.Statement.RETURN_GENERATED_KEYS
             )) {

            statement.setString(1, name);
            statement.setString(2, birth);
            statement.setString(3, sex);
            statement.setString(4, contact);
            statement.setString(5, addr);
            statement.setString(6, bairro);
            statement.setString(7, localidade);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return new Patient(
                            keys.getInt(1),
                            name,
                            birth,
                            sex,
                            contact,
                            addr,
                            bairro,
                            localidade
                    );
                }
            }

            throw new RuntimeException("Não foi possível obter o ID do paciente.");

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao registar paciente.", e);
        }
    }

    @Override
    public Patient findPatientById(int id) {
        String sql = """
                SELECT id, full_name, birth_date, gender, phone, address, neighborhood, city
                FROM patients
                WHERE id = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, id);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) return fromResult(result);
            }

            return emptyPatient();

        } catch (Exception e) {
            throw new RuntimeException("Erro ao procurar paciente.", e);
        }
    }

    @Override
    public Patient updatePatient(
            int id,
            String fullName,
            String birthDate,
            String gender,
            String phone,
            String address,
            String neighborhood,
            String city
    ) {
        String name = clean(fullName);
        String birth = clean(birthDate);
        String sex = clean(gender);
        String contact = clean(phone);
        String addr = clean(address);
        String bairro = clean(neighborhood);
        String localidade = clean(city);
        validate(name, birth, sex, contact, addr, bairro, localidade);

        String sql = """
                UPDATE patients
                SET full_name = ?, birth_date = ?, gender = ?, phone = ?,
                    address = ?, neighborhood = ?, city = ?
                WHERE id = ?
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, name);
            statement.setString(2, birth);
            statement.setString(3, sex);
            statement.setString(4, contact);
            statement.setString(5, addr);
            statement.setString(6, bairro);
            statement.setString(7, localidade);
            statement.setInt(8, id);

            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Paciente #" + id + " não encontrado.");
            }

            return findPatientById(id);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao actualizar paciente.", e);
        }
    }

    @Override
    public Patient[] listPatients() {
        String sql = """
                SELECT id, full_name, birth_date, gender, phone, address, neighborhood, city
                FROM patients
                ORDER BY id
                """;

        List<Patient> patients = new ArrayList<>();

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) patients.add(fromResult(result));
            return patients.toArray(new Patient[0]);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao listar pacientes.", e);
        }
    }

    private static Patient fromResult(ResultSet result) throws java.sql.SQLException {
        return new Patient(
                result.getInt("id"),
                result.getString("full_name"),
                result.getString("birth_date"),
                result.getString("gender"),
                result.getString("phone"),
                result.getString("address"),
                result.getString("neighborhood"),
                result.getString("city")
        );
    }

    private static Patient emptyPatient() {
        return new Patient(0, "", "", "", "", "", "", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\s+", " ");
    }

    private static void validate(
            String name, String birth, String sex, String contact,
            String address, String neighborhood, String city
    ) {
        if (name.isBlank()) throw new IllegalArgumentException("O nome completo é obrigatório.");
        if (birth.isBlank()) throw new IllegalArgumentException("A data de nascimento é obrigatória.");
        if (sex.isBlank()) throw new IllegalArgumentException("O género é obrigatório.");
        if (contact.isBlank()) throw new IllegalArgumentException("O contacto telefónico é obrigatório.");
        if (address.isBlank()) throw new IllegalArgumentException("A morada é obrigatória.");
        if (neighborhood.isBlank()) throw new IllegalArgumentException("O bairro é obrigatório.");
        if (city.isBlank()) throw new IllegalArgumentException("A cidade é obrigatória.");
    }
}
