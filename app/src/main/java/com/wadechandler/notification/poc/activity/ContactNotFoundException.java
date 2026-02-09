package com.wadechandler.notification.poc.activity;

/**
 * Thrown by {@code pollForContact} when a contact has not yet materialized (404).
 * This exception is retryable — Temporal's RetryOptions on the polling activity stub
 * will automatically retry with exponential backoff until the contact appears.
 */
public class ContactNotFoundException extends RuntimeException {

    public ContactNotFoundException(String externalIdType, String externalIdValue) {
        super("Contact not found: " + externalIdType + "=" + externalIdValue);
    }
}
