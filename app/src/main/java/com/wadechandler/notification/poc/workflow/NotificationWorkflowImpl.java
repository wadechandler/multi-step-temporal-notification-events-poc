package com.wadechandler.notification.poc.workflow;

import com.wadechandler.notification.poc.activity.ContactActivities;
import com.wadechandler.notification.poc.activity.MessageActivities;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Saga implementation for processing notification events.
 * <p>
 * This workflow is NOT a Spring bean — Temporal instantiates it.
 * All I/O happens via activity stubs; the workflow logic is purely deterministic.
 */
public class NotificationWorkflowImpl implements NotificationWorkflow {

    private static final Logger log = Workflow.getLogger(NotificationWorkflowImpl.class);

    /**
     * Standard activity stub for getContact, createContact — uses default retry policy.
     */
    private final ContactActivities contactActivities = Workflow.newActivityStub(
            ContactActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    /**
     * Polling activity stub for pollForContact — aggressive retries with backoff.
     * ContactNotFoundException is retryable (the "not yet consistent" signal).
     */
    private final ContactActivities pollingContactActivities = Workflow.newActivityStub(
            ContactActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(10)
                            .build())
                    .build());

    /**
     * Standard activity stub for createMessage.
     */
    private final MessageActivities messageActivities = Workflow.newActivityStub(
            MessageActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    @Override
    public void processNotification(UUID eventId, String eventType, NotificationPayload payload) {
        log.info("Processing notification event: {} type: {}", eventId, eventType);

        // Step 1: Resolve all contacts in parallel
        List<Promise<ContactResult>> contactPromises = payload.contacts().stream()
                .map(contact -> Async.function(() -> resolveContact(contact)))
                .toList();
        Promise.allOf(contactPromises).get();

        // Step 2: Create messages for each resolved contact
        List<ContactResult> resolvedContacts = contactPromises.stream()
                .map(Promise::get)
                .toList();

        for (ContactResult contact : resolvedContacts) {
            messageActivities.createMessage(contact.id(), payload.templateId(), eventType);
        }

        log.info("Notification workflow completed for event: {} ({} contacts, {} messages)",
                eventId, resolvedContacts.size(), resolvedContacts.size());
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
