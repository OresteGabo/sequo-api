# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies

COPY src ./src
RUN ./gradlew --no-daemon bootJar && \
    JAR_FILE="$(find build/libs -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)" && \
    cp "$JAR_FILE" /workspace/app.jar

FROM eclipse-temurin:21-jre

RUN groupadd --system sequo && \
    useradd --system --gid sequo --home-dir /app --shell /usr/sbin/nologin sequo

WORKDIR /app

COPY --from=build --chown=sequo:sequo /workspace/app.jar /app/app.jar

USER sequo

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=docker
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
