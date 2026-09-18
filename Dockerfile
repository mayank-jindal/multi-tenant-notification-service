# Multi-stage build: the JDK is needed to compile but not to run, so the final image
# carries only a JRE and the jar. Keeps the deployed image to roughly a third of the size.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies are resolved in their own layer, so a source-only change does not re-download
# the entire dependency tree on every deploy.
COPY pom.xml .
RUN mvn -B dependency:go-offline -DskipTests

COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Runs as a non-root user. A container process with root privileges has no reason to exist
# here, and one compromised dependency is all it takes to matter.
RUN addgroup -S notifly && adduser -S notifly -G notifly
COPY --from=build /build/target/*.jar app.jar
RUN chown notifly:notifly app.jar
USER notifly

EXPOSE 8080

# Container-aware heap sizing. Without MaxRAMPercentage the JVM sizes its heap from the host's
# memory rather than the container limit, and gets OOM-killed on a small instance.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
