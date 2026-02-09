# Multi-stage build for the Notification POC application
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy Gradle wrapper and build files
COPY ../gradlew .
COPY ../gradle gradle
COPY ../settings.gradle .
COPY ../build.gradle .
COPY app/build.gradle app/build.gradle

# Download dependencies (cached layer)
RUN ./gradlew :app:dependencies --no-daemon || true

# Copy source
COPY app/src app/src

# Build
RUN ./gradlew :app:bootJar --no-daemon

# Runtime image
FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /workspace/app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
