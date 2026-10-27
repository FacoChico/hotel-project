FROM eclipse-temurin:21-jdk-jammy AS build
ARG APPLICATION
WORKDIR /workspace
COPY ${APPLICATION}/ ./
RUN --mount=type=cache,id=hotel-project-gradle,target=/root/.gradle,sharing=locked \
    sed -i 's/\r$//' gradlew && \
    sh ./gradlew --no-daemon --max-workers=2 bootJar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 hotel && useradd --uid 10001 --gid 10001 --create-home hotel
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/build/libs/*.jar /app/application.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0"
USER 10001:10001
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=24 \
    CMD curl --fail --silent http://127.0.0.1:${SERVER_PORT}/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
