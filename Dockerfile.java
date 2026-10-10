FROM eclipse-temurin:21-jdk-jammy AS build
ARG SERVICE_PATH=api-gateway
WORKDIR /build
COPY ${SERVICE_PATH}/mvnw ./mvnw
COPY ${SERVICE_PATH}/.mvn ./.mvn
COPY ${SERVICE_PATH}/pom.xml ./pom.xml
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY ${SERVICE_PATH}/src ./src
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar ./application.jar
USER app
ENV SERVER_PORT=8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD curl --fail --silent --output /dev/null "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
