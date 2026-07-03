# Build stage: compiles the app using Maven + JDK
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy Maven wrapper and pom.xml first so dependencies are cached
# separately from source code changes (faster rebuilds).
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B

# Now copy the rest of the source and build the jar.
COPY src src
RUN ./mvnw clean package -DskipTests -B

# Run stage: lightweight image with just the JRE and the built jar.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/curriculumai-0.0.1-SNAPSHOT.jar app.jar

# Render provides the PORT environment variable at runtime; Spring Boot
# reads it via server.port if set, otherwise defaults to 8080.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]