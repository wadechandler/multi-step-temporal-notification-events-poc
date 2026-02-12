package com.wadechandler.notification.poc.consumer;

import com.wadechandler.notification.poc.config.KafkaTopics;
import com.wadechandler.notification.poc.model.dto.ExternalEventRequest;
import com.wadechandler.notification.poc.model.dto.NotificationPayload;
import com.wadechandler.notification.poc.workflow.NotificationWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Kafka consumer on the {@code notification-events} topic.
 * Reads the {@code X-Event-Type} header to determine which workflow to invoke.
 * For {@code RxOrderNotification} events, parses the payload into a typed DTO
 * and starts a {@link NotificationWorkflow}.
 */
@Component
@Profile("ev-worker")
@Slf4j
public class NotificationEventConsumer {

    static final String RX_ORDER_NOTIFICATION = "RxOrderNotification";

    private final WorkflowClient workflowClient;
    private final ObjectMapper objectMapper;
    private final String taskQueue;

    public NotificationEventConsumer(
            WorkflowClient workflowClient,
            ObjectMapper objectMapper,
            @Value("${temporal.worker.task-queue}") String taskQueue) {
        this.workflowClient = workflowClient;
        this.objectMapper = objectMapper;
        this.taskQueue = taskQueue;
    }

    @KafkaListener(topics = KafkaTopics.NOTIFICATION_EVENTS_TOPIC, groupId = "notification-workflow-starter")
    public void consume(ConsumerRecord<String, String> record) {
        String eventType = extractEventType(record);

        if (RX_ORDER_NOTIFICATION.equals(eventType)) {
            processRxOrderNotification(record, eventType);
        } else {
            log.warn("Unknown event type: '{}'. Skipping message at offset {} partition {}.",
                    eventType, record.offset(), record.partition());
        }
    }

    /**
     * Extract the event type from the {@code X-Event-Type} Kafka header,
     * falling back to the message body if the header is absent.
     */
    String extractEventType(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("X-Event-Type");
        if (header != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        // Fall back to parsing the message body
        try {
            var event = objectMapper.readValue(record.value(), ExternalEventRequest.class);
            return event.eventType();
        } catch (Exception e) {
            log.error("Failed to extract eventType from message body", e);
            return null;
        }
    }

    private void processRxOrderNotification(ConsumerRecord<String, String> record, String eventType) {
        try {
            ExternalEventRequest event = objectMapper.readValue(record.value(), ExternalEventRequest.class);
            NotificationPayload payload = objectMapper.convertValue(event.payload(), NotificationPayload.class);

            String workflowId = "notification-" + event.eventId();
            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(taskQueue)
                    .setWorkflowExecutionTimeout(Duration.ofMinutes(10))
                    .build();

            NotificationWorkflow workflow = workflowClient.newWorkflowStub(NotificationWorkflow.class, options);
            WorkflowClient.start(workflow::processNotification, event.eventId(), eventType, payload);

            log.info("Started NotificationWorkflow {} for event {}", workflowId, event.eventId());
        } catch (Exception e) {
            // Production: dead-letter the message or publish to a DLQ topic for manual review.
            // For the POC, logging the error is sufficient.
            log.error("Failed to process RxOrderNotification event at offset {} partition {}",
                    record.offset(), record.partition(), e);
        }
    }
}
