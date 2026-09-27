package mz.hospital.server;

import Hospital.*;
import mz.hospital.server.db.DatabaseManager;
import org.omg.CORBA.ORB;
import org.omg.PortableServer.POA;
import org.omg.PortableServer.POAHelper;
import org.omg.CosNaming.NameComponent;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;

public class Main {

    public static void main(String[] args) {

        try {

            DatabaseManager.initialize();

            ORB orb = ORB.init(args, null);

            POA rootPOA = POAHelper.narrow(
                    orb.resolve_initial_references("RootPOA")
            );

            rootPOA.the_POAManager().activate();

            org.omg.CORBA.Object namingReference =
                    orb.resolve_initial_references("NameService");

            NamingContextExt namingContext =
                    NamingContextExtHelper.narrow(namingReference);

            // HelloService

            HelloServiceImpl helloService =
                    new HelloServiceImpl();

            org.omg.CORBA.Object helloReference =
                    rootPOA.servant_to_reference(helloService);

            HelloService helloServiceRef =
                    HelloServiceHelper.narrow(helloReference);

            NameComponent[] helloName =
                    namingContext.to_name("HelloService");

            namingContext.rebind(
                    helloName,
                    helloServiceRef
            );

            System.out.println(
                    "HelloService registado no Naming Service."
            );

            // AuthService

            AuthServiceImpl authService =
                    new AuthServiceImpl();

            org.omg.CORBA.Object authReference =
                    rootPOA.servant_to_reference(authService);

            AuthService authServiceRef =
                    AuthServiceHelper.narrow(authReference);

            NameComponent[] authName =
                    namingContext.to_name("AuthService");

            namingContext.rebind(
                    authName,
                    authServiceRef
            );

            System.out.println(
                    "AuthService registado no Naming Service."
            );

            // PatientService

            PatientServiceImpl patientService =
                    new PatientServiceImpl();

            org.omg.CORBA.Object patientReference =
                    rootPOA.servant_to_reference(patientService);

            PatientService patientServiceRef =
                    PatientServiceHelper.narrow(patientReference);

            NameComponent[] patientName =
                    namingContext.to_name("PatientService");

            namingContext.rebind(
                    patientName,
                    patientServiceRef
            );

            System.out.println(
                    "PatientService registado no Naming Service."
            );

            // QueueService

            QueueServiceImpl queueService =
                    new QueueServiceImpl();

            org.omg.CORBA.Object queueReference =
                    rootPOA.servant_to_reference(queueService);

            QueueService queueServiceRef =
                    QueueServiceHelper.narrow(queueReference);

            NameComponent[] queueName =
                    namingContext.to_name("QueueService");

            namingContext.rebind(
                    queueName,
                    queueServiceRef
            );

            System.out.println(
                    "QueueService registado no Naming Service."
            );

            // AppointmentService

            AppointmentServiceImpl appointmentService =
                    new AppointmentServiceImpl();

            org.omg.CORBA.Object appointmentReference =
                    rootPOA.servant_to_reference(
                            appointmentService
                    );

            AppointmentService appointmentServiceRef =
                    AppointmentServiceHelper.narrow(
                            appointmentReference
                    );

            NameComponent[] appointmentName =
                    namingContext.to_name(
                            "AppointmentService"
                    );

            namingContext.rebind(
                    appointmentName,
                    appointmentServiceRef
            );

            System.out.println(
                    "AppointmentService registado no Naming Service."
            );

            System.out.println("Servidor CORBA iniciado.");

            orb.run();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}