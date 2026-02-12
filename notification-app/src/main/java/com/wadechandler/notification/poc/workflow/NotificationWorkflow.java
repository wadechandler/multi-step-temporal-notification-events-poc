package com.wadechandler.notification.poc.workflow;

import com.wadechandler.notification.poc.model.dto.NotificationPayload;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.UUID;

@WorkflowInterface
public interface NotificationWorkflow {

    /**
     * Process a notification event by resolving all contacts and creating messages.
     *
     * @param eventId   unique identifier for the external event
     * @param eventType the event type (e.g., "RxOrderNotification") for observability and routing
     * @param payload   the typed notification payload with contacts and template info
     */
    @WorkflowMethod
    void processNotification(UUID eventId, String eventType, NotificationPayload payload);
}
