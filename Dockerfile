# Build multi-stage, come al passo 5.1 di shows-service.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Prima il pom, poi il src: se il pom non cambia, Docker riusa lo strato con
# le dipendenze gia' scaricate.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# -DskipTests: i test girano nella pipeline, non nella build dell'immagine.
# BookingRepositoryIT usa Testcontainers, che qui dentro pretenderebbe Docker
# DENTRO Docker: una complicazione che non serve.
RUN mvn -B clean package -DskipTests

# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Utente non root: se qualcuno esce dal processo, non esce da root.
RUN addgroup -S cinema && adduser -S cinema -G cinema
USER cinema

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
