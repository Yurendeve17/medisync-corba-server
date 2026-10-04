package mz.hospital.client;

import Hospital.Patient;
import Hospital.PatientService;
import Hospital.PatientServiceHelper;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;

public class PatientTestClient {

    public static void main(String[] args) {

        try {
            ORB orb = ORB.init(args, null);

            org.omg.CORBA.Object namingReference =
                    orb.resolve_initial_references("NameService");

            NamingContextExt namingContext =
                    NamingContextExtHelper.narrow(namingReference);

            org.omg.CORBA.Object object =
                    namingContext.resolve_str("PatientService");

            PatientService patientService =
                    PatientServiceHelper.narrow(object);

            Patient patient = patientService.registerPatient(
                    "João Manuel",
                    "2001-05-15",
                    "Masculino",
                    "841234567",
                    "Av. Eduardo Mondlane, Nº 123",
                    "Sommerschield",
                    "Maputo"
            );

            System.out.println("Paciente registado:");
            System.out.println("ID: " + patient.id);
            System.out.println("Nome: " + patient.fullName);
            System.out.println("Data de nascimento: " + patient.birthDate);
            System.out.println("Género: " + patient.gender);
            System.out.println("Telefone: " + patient.phone);
            System.out.println("Morada: " + patient.address);
            System.out.println("Bairro: " + patient.neighborhood);
            System.out.println("Cidade: " + patient.city);

            Patient found =
                    patientService.findPatientById(patient.id);

            System.out.println("\nPaciente encontrado:");
            System.out.println("Nome: " + found.fullName);

            Patient[] patients =
                    patientService.listPatients();

            System.out.println("\nTotal de pacientes: " + patients.length);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}