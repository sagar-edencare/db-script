# ---------- Stage 1: build the jar ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# Copy pom first so dependency download is cached when only code changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Stage 2: tiny runtime image ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Run as non-root for security.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/target/db-migration-service.jar app.jar

# No EXPOSE: this container does not serve traffic. It runs once and exits.
# All config comes from environment variables at runtime (see README).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
