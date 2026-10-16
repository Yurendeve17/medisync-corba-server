package mz.hospital.server;

import Hospital.Patient;
import Hospital.PatientServicePOA;
import mz.hospital.server.db.DatabaseManager;
import mz.hospital.server.db.AuditLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.text.Normalizer;

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
                (full_name, birth_date, gender, phone, address, neighborhood, city, registered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     sql,
                     java.sql.Statement.RETURN_GENERATED_KEYS
             )) {

            connection.setAutoCommit(false);

            if (hasPossibleDuplicate(connection, name, birth, contact, 0)) {
                throw new IllegalArgumentException("Possível duplicado: já existe um paciente com este nome e data de nascimento ou com este telefone.");
            }
            statement.setString(1, name);
            statement.setString(2, birth);
            statement.setString(3, sex);
            statement.setString(4, contact);
            statement.setString(5, addr);
            statement.setString(6, bairro);
            statement.setString(7, localidade);
            statement.setString(8, java.time.ZonedDateTime.now(hospitalZone()).toString());
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    int patientId = keys.getInt(1);
                    AuditLogger.log(connection, null, "", "PATIENT_CREATED", "PATIENT", patientId, "");
                    connection.commit();
                    return new Patient(
                            patientId,
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

            connection.setAutoCommit(false);

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

            if (id <= 0 || hasPossibleDuplicate(connection, name, birth, contact, id)) {
                throw new IllegalArgumentException("Possível duplicado: já existe outro paciente com este nome e data de nascimento ou com este telefone.");
            }
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
            AuditLogger.log(connection, null, "", "PATIENT_UPDATED", "PATIENT", id, "");
            connection.commit();

            return findPatientById(id);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Erro ao actualizar paciente.", e);
        }
    }

    @Override
    public Patient[] searchPatients(String query) {
        String term = query == null ? "" : query.trim();
        if (term.isBlank()) return listPatients();
        List<Patient> matches = new ArrayList<>();
        String sql = "SELECT id,full_name,birth_date,gender,phone,address,neighborhood,city FROM patients WHERE CAST(id AS TEXT) LIKE ? OR lower(full_name) LIKE lower(?) OR phone LIKE ? ORDER BY full_name LIMIT 100";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            String wildcard = "%" + term + "%";
            ps.setString(1, wildcard); ps.setString(2, wildcard); ps.setString(3, wildcard);
            try (ResultSet r = ps.executeQuery()) { while (r.next()) matches.add(fromResult(r)); }
            return matches.toArray(new Patient[0]);
        } catch (Exception e) { throw new RuntimeException("Erro ao pesquisar pacientes.", e); }
    }

    @Override
    public boolean hasPossibleDuplicate(String fullName, String birthDate, String phone, int excludeId) {
        try (Connection c = DatabaseManager.getConnection()) {
            return hasPossibleDuplicate(c, clean(fullName), clean(birthDate), clean(phone), excludeId);
        } catch (Exception e) { throw new RuntimeException("Erro ao verificar duplicados.", e); }
    }

    private boolean hasPossibleDuplicate(Connection c, String name, String birth, String phone, int excludeId) throws Exception {
        String sql = "SELECT full_name,birth_date,phone FROM patients WHERE id<>?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, excludeId);
            try (ResultSet r = ps.executeQuery()) {
                String normalizedName = normalize(name);
                String normalizedPhone = phone.replaceAll("\\D", "");
                while (r.next()) {
                    boolean sameNameAndBirth = normalizedName.equals(normalize(r.getString("full_name")))
                            && birth.equals(r.getString("birth_date"));
                    String existingPhone = r.getString("phone").replaceAll("\\D", "");
                    boolean samePhone = !normalizedPhone.isEmpty() && normalizedPhone.equals(existingPhone);
                    if (sameNameAndBirth || samePhone) return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase().trim().replaceAll("\\s+", " ");
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
        if (name.trim().split("\\s+").length < 2) throw new IllegalArgumentException("Indique o nome completo do paciente.");
        try {
            LocalDate parsed = LocalDate.parse(birth);
            if (parsed.isAfter(LocalDate.now(hospitalZone())))
                throw new IllegalArgumentException("A data de nascimento não pode ser futura.");
        } catch (DateTimeParseException e) { throw new IllegalArgumentException("A data de nascimento deve usar o formato AAAA-MM-DD."); }
        if (sex.isBlank()) throw new IllegalArgumentException("O género é obrigatório.");
        String digits = contact.replaceAll("\\D", "");
        if (digits.length() < 7 || digits.length() > 15) throw new IllegalArgumentException("O telefone deve conter entre 7 e 15 dígitos.");
        if (address.isBlank()) throw new IllegalArgumentException("A morada é obrigatória.");
        if (neighborhood.isBlank()) throw new IllegalArgumentException("O bairro é obrigatório.");
        if (city.isBlank()) throw new IllegalArgumentException("A cidade é obrigatória.");
    }

    private static java.time.ZoneId hospitalZone() {
        try { return java.time.ZoneId.of(System.getenv().getOrDefault("HOSPITAL_TIMEZONE", "Africa/Maputo")); }
        catch (Exception e) { return java.time.ZoneId.of("Africa/Maputo"); }
    }
}
