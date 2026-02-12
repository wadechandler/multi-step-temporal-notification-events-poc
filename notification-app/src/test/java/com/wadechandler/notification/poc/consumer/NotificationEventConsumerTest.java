package com.wadechandler.notification.poc.consumer;

import com.wadechandler.notification.poc.config.KafkaTopics;
import com.wadechandler.notification.poc.workflow.NotificationWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationEventConsumerTest {

    @Mock
    private WorkflowClient workflowClient;

    private ObjectMapper objectMapper;
    private NotificationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        consumer = new NotificationEventConsumer(workflowClient, objectMapper, "NOTIFICATION_QUEUE");
    }

    @Test
    void unknownEventType_shouldSkipAndNotStartWorkflow() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.NOTIFICATION_EVENTS_TOPIC, 0, 0, "key", "{}");
        record.headers().add("X-Event-Type", "UnknownType".getBytes(StandardCharsets.UTF_8));

        consumer.consume(record);

        verifyNoInteractions(workflowClient);
    }

    @Test
    void nullEventType_shouldSkipAndNotStartWorkflow() {
        // No X-Event-Type header, and body has no eventType field
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.NOTIFICATION_EVENTS_TOPIC, 0, 0, "key",
                "{\"eventId\":\"00000000-0000-0000-0000-000000000001\"}");

        consumer.consume(record);

        verifyNoInteractions(workflowClient);
    }

    @Test
    void rxOrderNotification_shouldCreateWorkflowStubWithCorrectOptions() {
        // Full event body with typed payload
        String body = """
                {
                    "eventId": "00000000-0000-0000-0000-000000000001",
                    "eventType": "RxOrderNotification",
                    "payload": {
                        "contacts": [
                            {"externalIdType": "patientId", "externalIdValue": "ext-1", "email": "a@b.com"}
                        ],
                        "templateId": "rx-v1"
                    }
                }
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.NOTIFICATION_EVENTS_TOPIC, 0, 0, "key", body);
        record.headers().add("X-Event-Type", "RxOrderNotification".getBytes(StandardCharsets.UTF_8));

        NotificationWorkflow mockWorkflow = mock(NotificationWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(NotificationWorkflow.class), any(WorkflowOptions.class)))
                .thenReturn(mockWorkflow);

        // consume() calls WorkflowClient.start() which is a static method that requires
        // a real Temporal proxy. With a Mockito mock it will throw, but the consumer's
        // catch-all handles it gracefully. We verify the stub creation happened correctly.
        consumer.consume(record);

        var captor = ArgumentCaptor.forClass(WorkflowOptions.class);
        verify(workflowClient).newWorkflowStub(eq(NotificationWorkflow.class), captor.capture());

        WorkflowOptions options = captor.getValue();
        assertEquals("notification-00000000-0000-0000-0000-000000000001", options.getWorkflowId());
        assertEquals("NOTIFICATION_QUEUE", options.getTaskQueue());
    }

    @Test
    void extractEventType_fromHeader() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.NOTIFICATION_EVENTS_TOPIC, 0, 0, "key", "{}");
        record.headers().add("X-Event-Type", "RxOrderNotification".getBytes(StandardCharsets.UTF_8));

        String eventType = consumer.extractEventType(record);

        assertEquals("RxOrderNotification", eventType);
    }

    @Test
    void extractEventType_fromBody_whenHeaderMissing() {
        String body = """
                {
                    "eventId": "00000000-0000-0000-0000-000000000001",
                    "eventType": "RxOrderNotification",
                    "payload": {}
                }
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.NOTIFICATION_EVENTS_TOPIC, 0, 0, "key", body);

        String eventType = consumer.extractEventType(record);

        assertEquals("RxOrderNotification", eventType);
    }

    @Test
    void extractEventType_returnsNull_whenNoHeaderAndInvalidBody() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                KafkaTopics.NOTIFICATION_EVENTS_TOPIC, 0, 0, "key", "not-json");

        String eventType = consumer.extractEventType(record);

        assertNull(eventType);
    }
}
