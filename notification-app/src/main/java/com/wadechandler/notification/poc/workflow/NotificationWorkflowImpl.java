package com.wadechandler.notification.poc.workflow;

import com.wadechandler.notification.poc.activity.ContactActivities;
import com.wadechandler.notification.poc.activity.MessageActivities;
import com.wadechandler.notification.poc.config.TaskQueues;
import com.wadechandler.notification.poc.model.dto.ContactInfo;
import com.wadechandler.notification.poc.model.dto.ContactResult;
import com.wadechandler.notification.poc.model.dto.NotificationPayload;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Saga implementation for processing notification events.
 * <p>
 * This workflow is NOT a Spring bean — Temporal instantiates it.
 * All I/O happens via activity stubs; the workflow logic is purely deterministic.
 * <p>
 * The workflow itself runs on {@code NOTIFICATION_QUEUE}. Activity tasks are routed
 * to specific queues via {@code ActivityOptions.setTaskQueue()}:
 * <ul>
 *   <li>Contact activities → {@code CONTACT_ACTIVITY_QUEUE}</li>
 *   <li>Message activities → {@code MESSAGE_ACTIVITY_QUEUE}</li>
 * </ul>
 */
public class NotificationWorkflowImpl implements NotificationWorkflow {

    private static final Logger log = Workflow.getLogger(NotificationWorkflowImpl.class);

    /**
     * Standard activity stub for getContact, createContact — uses default retry policy.
     * Routes to CONTACT_ACTIVITY_QUEUE.
     */
    private final ContactActivities contactActivities = Workflow.newActivityStub(
            ContactActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.CONTACT_ACTIVITY_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    /**
     * Polling activity stub for pollForContact — aggressive retries with backoff.
     * ContactNotFoundException is retryable (the "not yet consistent" signal).
     * Routes to CONTACT_ACTIVITY_QUEUE.
     */
    private final ContactActivities pollingContactActivities = Workflow.newActivityStub(
            ContactActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.CONTACT_ACTIVITY_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(10)
                            .build())
                    .build());

    /**
     * Standard activity stub for createMessage.
     * Routes to MESSAGE_ACTIVITY_QUEUE.
     */
    private final MessageActivities messageActivities = Workflow.newActivityStub(
            MessageActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.MESSAGE_ACTIVITY_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    @Override
    public void processNotification(UUID eventId, String eventType, NotificationPayload payload) {
        log.info("Processing notification event: {} type: {}", eventId, eventType);

        // --- Phase 1: Resolve all contacts in parallel (CONTACT_ACTIVITY_QUEUE) ---
        List<Promise<ContactResult>> contactPromises = payload.contacts().stream()
                .map(contact -> Async.function(() -> resolveContact(contact)))
                .toList();
        Promise.allOf(contactPromises).get();

        List<ContactResult> resolvedContacts = contactPromises.stream()
                .map(Promise::get)
                .toList();

        log.info("All {} contacts resolved for event: {}", resolvedContacts.size(), eventId);

        // --- Phase 2: Bundle by unique endpoint, create messages (MESSAGE_ACTIVITY_QUEUE) ---
        // Group contacts by unique phone/email to avoid duplicate messages.
        // In production, this would also consider channel preferences (voice/SMS/email).
        Map<String, List<ContactResult>> byEmail = resolvedContacts.stream()
                .filter(c -> c.email() != null && !c.email().isBlank())
                .collect(Collectors.groupingBy(ContactResult::email));

        // TODO: Production would also group by phone for SMS/voice channels,
        //       check contact preferences, and potentially create multiple messages
        //       per contact (e.g., SMS + email). For the POC, we create one message
        //       per unique email endpoint.
        for (var entry : byEmail.entrySet()) {
            // Use the first contact in the group as the primary recipient
            ContactResult primary = entry.getValue().get(0);
            messageActivities.createMessage(primary.id(), payload.templateId(), eventType);
        }

        // Also handle contacts with no email (phone-only in production)
        resolvedContacts.stream()
                .filter(c -> c.email() == null || c.email().isBlank())
                .forEach(contact -> {
                    // TODO: In production, route to SMS/voice based on phone + preferences
                    messageActivities.createMessage(contact.id(), payload.templateId(), eventType);
                });

        long messageCount = byEmail.size() + resolvedContacts.stream()
                .filter(c -> c.email() == null || c.email().isBlank()).count();
        log.info("Notification workflow completed for event: {} ({} contacts, {} messages)",
                eventId, resolvedContacts.size(), messageCount);
    }

    /**
     * Resolve a single contact: look up by external ID, create if not found, poll until materialized.
     */
    private ContactResult resolveContact(ContactInfo contact) {
        // Try to find the existing contact
        Optional<ContactResult> existing = contactActivities.getContact(
                contact.externalIdType(), contact.externalIdValue());

        if (existing.isPresent()) {
            log.info("Contact already exists: {}={} -> id={}",
                    contact.externalIdType(), contact.externalIdValue(), existing.get().id());
            return existing.get();
        }

        // Contact doesn't exist — create it and poll for eventual consistency
        log.info("Contact not found, creating: {}={}", contact.externalIdType(), contact.externalIdValue());
        contactActivities.createContact(contact);

        // Poll with exponential backoff (via pollingContactActivities RetryOptions)
        return pollingContactActivities.pollForContact(
                contact.externalIdType(), contact.externalIdValue());
    }
}
