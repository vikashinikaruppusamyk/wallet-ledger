# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

# Run (the same image serves as the API and the background worker)
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 wallet
COPY --from=build /src/target/wallet-ledger-1.0.0.jar app.jar
USER wallet
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
