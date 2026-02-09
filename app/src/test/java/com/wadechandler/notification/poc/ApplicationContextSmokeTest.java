package com.wadechandler.notification.poc;

import com.wadechandler.notification.poc.controller.ContactController;
import com.wadechandler.notification.poc.controller.EventController;
import com.wadechandler.notification.poc.controller.MessageController;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test that boots the FULL Spring Boot application context with real
 * Postgres and Kafka (via Testcontainers). This catches auto-configuration
 * failures that unit tests with mocks cannot detect.
 * <p>
 * The Temporal beans (WorkflowServiceStubs, WorkflowClient, WorkerFactory)
 * are mocked because they require a running Temporal server.
 */
@SpringBootTest
@Testcontainers
class ApplicationContextSmokeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("business")
            .withUsername("app")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    // Mock the Temporal beans — they need a running Temporal server
    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private WorkerFactory workerFactory;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("temporal.connection.target", () -> "localhost:17233");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void kafkaTemplateIsAutoConfigured() {
        assertThat(context.getBean(KafkaTemplate.class)).isNotNull();
    }

    @Test
    void controllersAreWired() {
        assertThat(context.getBean(EventController.class)).isNotNull();
        assertThat(context.getBean(ContactController.class)).isNotNull();
        assertThat(context.getBean(MessageController.class)).isNotNull();
    }

    @Test
    void flywayRanAndTablesExist() {
        // If Flyway ran, Hibernate validation (ddl-auto=validate) would have
        // succeeded during context startup. The fact that we got here means
        // Flyway created the tables and Hibernate validated them successfully.
        assertThat(context.getBean("entityManagerFactory")).isNotNull();
    }
}
