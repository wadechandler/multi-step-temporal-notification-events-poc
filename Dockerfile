# Multi-stage build for the Notification POC application
# Usage: docker build -t notification-poc:latest .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY settings.gradle .
COPY build.gradle .
COPY notification-common/build.gradle notification-common/build.gradle
COPY notification-repository/build.gradle notification-repository/build.gradle
COPY notification-messaging/build.gradle notification-messaging/build.gradle
COPY notification-app/build.gradle notification-app/build.gradle

# Download dependencies (cached layer)
RUN ./gradlew :notification-app:dependencies --no-daemon || true

# Copy source for all modules
COPY notification-common/src notification-common/src
COPY notification-repository/src notification-repository/src
COPY notification-messaging/src notification-messaging/src
COPY notification-app/src notification-app/src

# Build
RUN ./gradlew :notification-app:bootJar --no-daemon

# Runtime image
FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /workspace/notification-app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
