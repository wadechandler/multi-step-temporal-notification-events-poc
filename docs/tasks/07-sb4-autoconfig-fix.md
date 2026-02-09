# Task 07: Fix Spring Boot 4 Auto-Configuration and Add Integration Tests

## Context

When running the application against real infrastructure (Task 06), the app fails to start with
multiple missing bean errors. The root cause is Spring Boot 4's modularization of auto-configuration.

### Root Cause: Spring Boot 4 Modular Starters

Spring Boot 4.0 split the monolithic `spring-boot-autoconfigure` module into separate per-technology
modules. Technologies that previously auto-configured just by having the library on the classpath now
require an explicit Spring Boot starter dependency.

From the [SB4 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide):
> Features that only relied on a third-party dependency to work can require an additional starter.

### Affected Dependencies

| What broke | Old (SB3 style) | New (SB4 required) |
|---|---|---|
| Kafka auto-config (KafkaTemplate, @KafkaListener) | `spring-kafka` | `spring-boot-starter-kafka` |
| Flyway auto-config (migrations) | `flyway-core` + `flyway-database-postgresql` | `spring-boot-starter-flyway` (+ keep flyway-database-postgresql) |
| RestClient.Builder auto-config | Included in `spring-boot-starter-web` (SB3) | `spring-boot-starter-restclient` |

Additionally, the SB4 migration guide deprecates `spring-boot-starter-web` in favor of
`spring-boot-starter-webmvc`, and recommends `spring-boot-starter-kafka-test` for test code.

### Why Tests Didn't Catch This

All existing tests use either:
- `@WebMvcTest` (thin slices that mock KafkaTemplate, repos — never boots full context)
- `@ExtendWith(MockitoExtension.class)` (pure Mockito, no Spring context at all)
- `TestWorkflowEnvironment` (Temporal's test framework, no Spring)

No test boots the full `@SpringBootTest` application context, so the auto-configuration
failures were invisible. The code compiles fine; only the runtime wiring fails.

## Prerequisites
- Tasks 04, 04a, 05 completed (code exists but app doesn't start)

## Deliverables

### 1. Fix `app/build.gradle` Dependencies

Replace raw library dependencies with SB4 starters:

```groovy
dependencies {
    // Spring Boot (SB4 modular starters)
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // Kafka (SB4 starter includes spring-kafka + auto-configuration)
    implementation 'org.springframework.boot:spring-boot-starter-kafka'

    // RestClient (SB4 requires explicit starter for RestClient.Builder bean)
    implementation 'org.springframework.boot:spring-boot-starter-restclient'

    // Flyway (SB4 starter includes flyway-core + auto-configuration)
    implementation 'org.springframework.boot:spring-boot-starter-flyway'
    runtimeOnly 'org.flywaydb:flyway-database-postgresql'

    // Temporal SDK (no SB4 starter exists — manual bean config is correct)
    implementation 'io.temporal:temporal-sdk:1.32.1'
    implementation 'io.temporal:temporal-opentracing:1.32.1'

    // Database
    runtimeOnly 'org.postgresql:postgresql'

    // Observability
    implementation 'io.micrometer:micrometer-registry-prometheus'

    // Utilities
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testCompileOnly 'org.projectlombok:lombok'
    testAnnotationProcessor 'org.projectlombok:lombok'

    // Testing (SB4 modular test starters)
    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-kafka-test'
    testImplementation 'io.temporal:temporal-testing:1.32.1'
    testImplementation platform('org.testcontainers:testcontainers-bom:1.20.6')
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'org.testcontainers:postgresql'
    testImplementation 'org.testcontainers:kafka'
}
```

### 2. Remove Manual `RestClientConfig.java`

The `RestClientConfig` bean created as a workaround is no longer needed once
`spring-boot-starter-restclient` is on the classpath.

### 3. Add `@SpringBootTest` Smoke Test

Create `ApplicationContextSmokeTest.java` that boots the full context with Testcontainers:

```java
@SpringBootTest
@Testcontainers
class ApplicationContextSmokeTest {
    // Postgres + Kafka containers
    // Verifies: context loads, all beans wire, Flyway runs, schema valid
}
```

This is the test that would have caught every issue we hit.

### 4. Update `00-project-standards.mdc`

Add a note about SB4 modular starters to prevent future regressions.

## Acceptance Criteria

- `./gradlew :app:compileJava` succeeds
- `./gradlew :app:test` succeeds (all existing + new smoke test)
- Application starts successfully with `./gradlew :app:bootRun` against live infra
- Flyway runs and creates tables automatically
- KafkaTemplate is auto-configured
- RestClient.Builder is auto-configured

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/07-sb4-autoconfig-fix.md (this task)
- app/build.gradle (current broken dependencies)
- app/src/main/java/com/wadechandler/notification/poc/config/RestClientConfig.java (workaround to remove)
- .cursor/rules/00-project-standards.mdc (update with SB4 notes)

Task: Fix the Spring Boot 4 auto-configuration issues per this task document.

1. Update app/build.gradle to use SB4 modular starters
2. Delete RestClientConfig.java (no longer needed)
3. Add ApplicationContextSmokeTest with Testcontainers
4. Run ./gradlew :app:test and verify all tests pass
5. Run the app against live infra and verify it starts
```
