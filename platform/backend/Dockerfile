FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
# Run as an unprivileged user: a compromised process cannot write the image or bind low ports.
RUN groupadd --system serv60 && useradd --system --gid serv60 --no-create-home serv60
WORKDIR /app
COPY --from=build --chown=serv60:serv60 /workspace/target/serv60-platform-0.1.0-SNAPSHOT.jar app.jar
USER serv60
EXPOSE 8080
# JSON (ECS) logs with trace and request ids for the log pipeline; plain text stays the local default.
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
# Size the heap from the container limit; the default 25% wastes most of a small task's memory.
# The remaining quarter covers metaspace, thread stacks, direct buffers and the code cache.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
