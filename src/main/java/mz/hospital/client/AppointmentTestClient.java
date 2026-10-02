package mz.hospital.client;

import Hospital.Appointment;
import Hospital.AppointmentService;
import Hospital.AppointmentServiceHelper;
import org.omg.CORBA.ORB;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;

public class AppointmentTestClient {

    public static void main(String[] args) {

        try {
            ORB orb = ORB.init(args, null);

            org.omg.CORBA.Object namingReference =
                    orb.resolve_initial_references("NameService");

            NamingContextExt namingContext =
                    NamingContextExtHelper.narrow(namingReference);

            org.omg.CORBA.Object object =
                    namingContext.resolve_str("AppointmentService");

            AppointmentService appointmentService =
                    AppointmentServiceHelper.narrow(object);

            Appointment appointment =
                    appointmentService.scheduleAppointment(
                            1,
                            "Dr. Carlos",
                            "2026-09-15 09:00",
                            "Clínica Geral"
                    );

            System.out.println("Consulta marcada:");
            System.out.println("ID: " + appointment.id);
            System.out.println("Paciente ID: " + appointment.patientId);
            System.out.println("Médico: " + appointment.doctor);
            System.out.println("Data: " + appointment.appointmentDate);
            System.out.println("Especialidade: " + appointment.specialty);
            System.out.println("Estado: " + appointment.status);

            appointmentService.updateAppointmentStatus(appointment.id, "AGUARDANDO");

            Appointment found =
                    appointmentService.findAppointmentById(appointment.id);

            System.out.println("\nConsulta encontrada:");
            System.out.println("Médico: " + found.doctor);
            System.out.println("Estado: " + found.status);

            Appointment[] appointments =
                    appointmentService.listAppointments();

            System.out.println(
                    "\nTotal de consultas: " + appointments.length
            );

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}