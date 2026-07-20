FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY payment-api/pom.xml payment-api/pom.xml
COPY saga-orchestrator/pom.xml saga-orchestrator/pom.xml
COPY fraud-service/pom.xml fraud-service/pom.xml
COPY funds-auth-service/pom.xml funds-auth-service/pom.xml
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY settlement-service/pom.xml settlement-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
RUN mvn -B -pl ledger-service -am dependency:go-offline
COPY common/src common/src
COPY ledger-service/src ledger-service/src
RUN mvn -B -pl ledger-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/ledger-service/target/ledger-service-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
