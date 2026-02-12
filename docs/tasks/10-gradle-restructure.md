# Task 10: Multi-Module Gradle Restructure

## Context

The POC is currently a single Gradle subproject (`app/`) containing all code: REST controllers,
CQRS command handlers, Kafka consumers, Temporal workflows/activities, and the worker
configuration. This works for local development but doesn't reflect how the system will be
deployed -- as separate Kubernetes workloads (service, event worker, workflow worker) each
running different subsets of the code.

This task restructures the project into a multi-module Gradle build with shared libraries,
then uses Spring `@Profile` annotations to control which components activate in each
deployment. A single Docker image is built; Helm deploys it multiple times with different
`SPRING_PROFILES_ACTIVE` values.

### Target Module Structure

```
notification-poc/                              (root)
  settings.gradle                              (includes all subprojects)
  build.gradle                                 (shared plugin/dependency config)
  notification-common/                         (DTOs, domain models, topic constants)
    build.gradle
    src/main/java/com/wadechandler/notification/poc/
      model/
        dto/
          ExternalEventRequest.java
          NotificationPayload.java
          ContactInfo.java
          ContactRequest.java
          ContactResult.java
          MessageRequest.java
          MessageResult.java
      config/
        KafkaTopics.java                       (topic name constants, extracted from KafkaConfig)
  notification-repository/                     (JPA entities, repositories, Flyway)
    build.gradle
    src/main/java/com/wadechandler/notification/poc/
      model/
        Contact.java                           (JPA entity)
        Message.java                           (JPA entity)
        ExternalEvent.java                     (JPA entity)
      repository/
        ContactRepository.java
        MessageRepository.java
        ExternalEventRepository.java
    src/main/resources/
      db/migration/
        V1__initial_schema.sql
  notification-messaging/                      (Kafka infra, producer/consumer config)
    build.gradle
    src/main/java/com/wadechandler/notification/poc/
      messaging/
        KafkaTopicConfig.java                  (topic NewTopic @Bean definitions)
  notification-app/                            (Single boot module - replaces app/)
    build.gradle
    Dockerfile
    src/main/java/com/wadechandler/notification/poc/
      NotificationPocApplication.java
      controller/                              (@Profile("service"))
        EventController.java
        ContactController.java
        MessageController.java
      command/                                 (@Profile("service"))
        ContactCommandHandler.java
        MessageCommandHandler.java
      consumer/                                (@Profile("ev-worker"))
        NotificationEventConsumer.java
      workflow/                                (@Profile("wf-worker"))
        NotificationWorkflow.java
        NotificationWorkflowImpl.java
      activity/                                (@Profile("wf-worker"))
        ContactActivities.java
        ContactActivitiesImpl.java
        MessageActivities.java
        MessageActivitiesImpl.java
        ContactNotFoundException.java
      worker/
        TemporalWorkerConfig.java              (@Profile("wf-worker"))
    src/main/resources/
      application.yml                          (shared config)
      application-service.yml                  (service-specific)
      application-ev-worker.yml                (ev-worker-specific)
      application-wf-worker.yml                (wf-worker-specific)
```

### Profile Strategy

Each deployment runs the same image with different `SPRING_PROFILES_ACTIVE`:

- **`service`**: Controllers, CQRS command handlers, DataSource, JPA, Flyway, web server, Kafka (for command topics). NO Temporal worker, NO event consumer.
- **`ev-worker`**: Kafka event consumer (`NotificationEventConsumer`), Temporal client (to start workflows). NO DataSource, NO Flyway, NO controllers. Web server runs only for actuator health/metrics.
- **`wf-worker`**: Temporal worker (workflows + activities), RestClient (activities call REST APIs). NO DataSource, NO Flyway, NO controllers, NO Kafka consumer. Web server runs only for actuator health/metrics.

For **local development**, run with `--spring.profiles.active=service,ev-worker,wf-worker` to get all components in one process (current behavior preserved).

### Profile-Specific Auto-Configuration

Deployments that don't use DataSource need to exclude it:

```yaml
# application-ev-worker.yml and application-wf-worker.yml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
      - org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
```

The wf-worker also doesn't need Kafka consumer auto-configuration (activities use RestClient, not Kafka):
```yaml
# application-wf-worker.yml
spring:
  kafka:
    listener:
      auto-startup: false
```

## Prerequisites
- Tasks 00-08 complete (current codebase is working)
- Understanding of the current single-module structure

## Key Files to Read
- `.cursor/rules/00-project-standards.mdc` — Current tech stack and patterns
- `settings.gradle` — Current project includes
- `build.gradle` — Root build config
- `app/build.gradle` — Current dependencies
- `app/src/main/resources/application.yml` — Current config
- `app/src/main/java/com/wadechandler/notification/poc/config/KafkaConfig.java` — Topic constants + beans to split
- All Java source files in `app/src/main/java/` to understand what moves where

## Deliverables

### 1. Create Shared Library Modules

**`notification-common/build.gradle`:**
```groovy
plugins {
    id 'java-library'
}

dependencies {
    // Jackson annotations for DTOs (version managed by Spring BOM via root)
    api 'com.fasterxml.jackson.core:jackson-annotations'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

**`notification-repository/build.gradle`:**
```groovy
plugins {
    id 'java-library'
}

dependencies {
    api project(':notification-common')

    api 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-flyway'
    runtimeOnly 'org.flywaydb:flyway-database-postgresql'
    runtimeOnly 'org.postgresql:postgresql'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

**`notification-messaging/build.gradle`:**
```groovy
plugins {
    id 'java-library'
}

dependencies {
    api project(':notification-common')

    api 'org.springframework.boot:spring-boot-starter-kafka'

    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

### 2. Update Root `build.gradle`

Apply `io.spring.dependency-management` to all subprojects so library modules get
Spring Boot BOM version management without applying the boot plugin:

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.2' apply false
    id 'io.spring.dependency-management' version '1.1.7' apply false
}

allprojects {
    group = 'com.wadechandler.notification.poc'
    version = '0.1.0-SNAPSHOT'
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'

    dependencyManagement {
        imports {
            mavenBom org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES
        }
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType(JavaCompile).configureEach {
        options.encoding = 'UTF-8'
        options.compilerArgs.add('--enable-preview')
    }

    tasks.withType(JavaExec).configureEach {
        jvmArgs('--enable-preview')
    }

    tasks.withType(Test).configureEach {
        useJUnitPlatform()
        jvmArgs('--enable-preview')
    }
}
```

### 3. Update `settings.gradle`

```groovy
rootProject.name = 'notification-poc'

include 'notification-common'
include 'notification-repository'
include 'notification-messaging'
include 'notification-app'
```

### 4. Rename `app/` to `notification-app/`

Rename the directory and update all references (Dockerfile paths, scripts, etc.).

### 5. Move Source Files

Move files from `notification-app` (formerly `app`) into the appropriate shared modules:

**To `notification-common`:**
- `model/dto/ExternalEventRequest.java`
- `model/dto/NotificationPayload.java`
- `model/dto/ContactInfo.java`
- `model/dto/ContactRequest.java`
- `model/dto/ContactResult.java`
- `model/dto/MessageRequest.java`
- `model/dto/MessageResult.java`
- Extract topic name constants from `KafkaConfig.java` into a new `config/KafkaTopics.java`

**To `notification-repository`:**
- `model/Contact.java` (JPA entity)
- `model/Message.java` (JPA entity)
- `model/ExternalEvent.java` (JPA entity)
- `repository/ContactRepository.java`
- `repository/MessageRepository.java`
- `repository/ExternalEventRepository.java`
- `resources/db/migration/V1__initial_schema.sql`

**To `notification-messaging`:**
- Extract topic `NewTopic` bean definitions from `KafkaConfig.java` into `messaging/KafkaTopicConfig.java`
- The `KafkaConfig.java` in notification-app may be removed or reduced to app-specific config only

**Stays in `notification-app`:**
- `NotificationPocApplication.java`
- `controller/*` (with `@Profile("service")`)
- `command/*` (with `@Profile("service")`)
- `consumer/NotificationEventConsumer.java` (with `@Profile("ev-worker")`)
- `workflow/*` (with `@Profile("wf-worker")`)
- `activity/*` (with `@Profile("wf-worker")`)
- `worker/TemporalWorkerConfig.java` (with `@Profile("wf-worker")`)

### 6. Add `@Profile` Annotations

Add Spring profile annotations to component classes and configuration:

```java
// Controllers
@RestController
@Profile("service")
@RequestMapping("/contacts")
public class ContactController { ... }

// Command handlers
@Component
@Profile("service")
public class ContactCommandHandler { ... }

// Kafka event consumer
@Component
@Profile("ev-worker")
public class NotificationEventConsumer { ... }

// Temporal worker config
@Configuration
@Profile("wf-worker")
public class TemporalWorkerConfig { ... }

// Activity implementations
@Component
@Profile("wf-worker")
public class ContactActivitiesImpl implements ContactActivities { ... }
```

### 7. Create Profile-Specific YAML Files

**`application.yml`** (shared across all profiles):
```yaml
spring:
  application:
    name: notification-poc
  threads:
    virtual:
      enabled: true
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:30092}

server:
  port: ${SERVER_PORT:8080}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

**`application-service.yml`:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:30432}/business
    username: ${DB_USER:app}
    password: ${BUSINESS_DB_PASSWORD:app}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
  kafka:
    consumer:
      group-id: notification-poc-service

services:
  base-url: ${SERVICES_BASE_URL:http://localhost:8080}
```

**`application-ev-worker.yml`:**
```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
      - org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
  kafka:
    consumer:
      group-id: notification-workflow-starter
      auto-offset-reset: earliest

temporal:
  connection:
    target: ${TEMPORAL_ADDRESS:localhost:7233}
```

**`application-wf-worker.yml`:**
```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
      - org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
  kafka:
    listener:
      auto-startup: false

temporal:
  connection:
    target: ${TEMPORAL_ADDRESS:localhost:7233}
  worker:
    task-queue: ${TEMPORAL_TASK_QUEUE:NOTIFICATION_QUEUE}

services:
  base-url: ${SERVICES_BASE_URL:http://notification-service:8080}
```

### 8. Update `.cursor/rules/` Files

Update the glob patterns and content in `.cursor/rules/20-temporal-workflows.mdc` to reflect
the new path: `notification-app/src/**/*.java` instead of `app/src/**/*.java`.

Update `.cursor/rules/00-project-standards.mdc` to document the multi-module structure and
Spring profile strategy.

### 9. Update References

- `scripts/e2e-test.sh`: Change `:app:bootRun` to `:notification-app:bootRun`
- `scripts/quick-test.sh`: Same
- Any other scripts referencing `app/` paths
- `Dockerfile`: Update build context paths (now at `notification-app/Dockerfile` or root)

## Acceptance Criteria

- [ ] `./gradlew compileJava` succeeds across all modules
- [ ] `./gradlew :notification-app:bootRun --args='--spring.profiles.active=service,ev-worker,wf-worker'` starts the app with all components (same as current behavior)
- [ ] Each profile can start independently without errors:
  - `--spring.profiles.active=service` starts web server + CQRS handlers
  - `--spring.profiles.active=ev-worker` starts Kafka consumer + Temporal client
  - `--spring.profiles.active=wf-worker` starts Temporal worker + activities
- [ ] The full e2e flow works: `POST /events` -> Kafka -> Temporal workflow -> contacts + messages created
- [ ] No circular dependencies between modules
- [ ] `.cursor/rules/` files updated with new paths and module structure
- [ ] Existing tests still pass: `./gradlew :notification-app:test`

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/10-gradle-restructure.md (this task — full module structure, build files, profile strategy)
- .cursor/rules/00-project-standards.mdc (current tech stack)
- settings.gradle (current project includes)
- build.gradle (root build config)
- app/build.gradle (current dependencies)
- app/src/main/resources/application.yml (current config)
- app/src/main/java/com/wadechandler/notification/poc/config/KafkaConfig.java (topic constants to split)

Also read ALL Java source files in app/src/main/java/ to understand what code moves where.

Task: Restructure the single-module project into a multi-module Gradle build per
the task document.

Steps:
1. Create notification-common/, notification-repository/, notification-messaging/ directories
   with build.gradle files and package structure.
2. Move source files from app/ into the appropriate modules per the mapping in the task.
3. Rename app/ to notification-app/.
4. Update settings.gradle to include all four subprojects.
5. Update root build.gradle with shared dependency management for all subprojects.
6. Update notification-app/build.gradle with project dependencies.
7. Add @Profile annotations to all component classes per the profile strategy.
8. Create application-service.yml, application-ev-worker.yml, application-wf-worker.yml.
9. Slim down application.yml to shared-only config.
10. Update .cursor/rules/ files (glob patterns, module documentation).
11. Update scripts that reference app/ paths (e2e-test.sh, quick-test.sh, etc.).
12. Verify: ./gradlew compileJava succeeds.
13. Verify: ./gradlew :notification-app:bootRun with all three profiles works.
14. Verify: each profile starts independently without errors.

Key rules:
- Do NOT change any business logic. This is a structural refactor only.
- Maintain the same package namespace: com.wadechandler.notification.poc
- All modules get Spring Boot BOM dependency management via the root build.gradle.
- Only notification-app/ applies the Spring Boot plugin (for bootJar/bootRun).
- Library modules use java-library plugin.
- @Profile annotations control which beans are active per deployment.
- For local dev, all three profiles run together (current behavior preserved).
```
