FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/target/serv60-platform-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
# JSON (ECS) logs with trace and request ids for the log pipeline; plain text stays the local default.
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
