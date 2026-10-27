FROM eclipse-temurin:21-jdk-jammy
WORKDIR /workspace
COPY discovery-server/ discovery-server/
COPY api-gateway/ api-gateway/
COPY hotel-service/ hotel-service/
COPY booking-service/ booking-service/
COPY docker/run-tests.sh /opt/run-tests.sh
RUN sed -i 's/\r$//' /opt/run-tests.sh */gradlew
ENV GRADLE_USER_HOME=/cache/gradle
ENTRYPOINT ["sh", "/opt/run-tests.sh"]
