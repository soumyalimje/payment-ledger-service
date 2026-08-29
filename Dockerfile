# Build and run the Payment Ledger Service in a single container.
# No Maven/Gradle needed -- matches how this project has been built all along
# (plain javac), just done inside a reproducible image instead of your own machine.

FROM eclipse-temurin:21-jdk

WORKDIR /app

# The PostgreSQL JDBC driver -- same one used throughout local development.
# ADD can fetch a URL directly, so we don't depend on curl being present in the base image.
ADD https://jdbc.postgresql.org/download/postgresql-42.7.4.jar postgresql.jar

COPY src/ src/

RUN javac -cp "postgresql.jar" -d out src/*.java

EXPOSE 8090

CMD ["java", "-cp", "out:postgresql.jar", "PaymentServer"]
