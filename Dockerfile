FROM maven:3.9-eclipse-temurin-21

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean compile
RUN mvn dependency:build-classpath "-Dmdep.outputFile=classpath.txt"

CMD ["sh", "-c", "java -DOAPort=1050 -Djacorb.ior_proxy_host=${CORBA_PUBLIC_HOST} -Djacorb.ior_proxy_port=1050 -cp target/classes:$(cat classpath.txt) org.jacorb.naming.NameServer"]