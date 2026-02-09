package com.wadechandler.notification.poc.activity;

import com.wadechandler.notification.poc.model.dto.ContactInfo;
import com.wadechandler.notification.poc.model.dto.ContactResult;
import io.temporal.activity.ActivityInterface;

import java.util.Optional;

@ActivityInterface
public interface ContactActivities {

    /**
     * Look up a contact by external ID.
     * Returns {@code Optional.empty()} on 404 — this is a normal result, not an error.
     * Uses default retry options (retries only on transient failures like network errors).
     */
    Optional<ContactResult> getContact(String externalIdType, String externalIdValue);

    /**
     * Request creation of a new contact. The POST returns 202 Accepted (fire-and-forget).
     * The actual creation happens asynchronously via the CQRS command pipeline.
     */
    void createContact(ContactInfo contactInfo);

    /**
     * Poll for a contact that was recently created. Throws {@link ContactNotFoundException}
     * on 404, which is retryable via the polling activity stub's RetryOptions.
     * Returns the contact once it has materialized in the database.
     */
    ContactResult pollForContact(String externalIdType, String externalIdValue);
}
