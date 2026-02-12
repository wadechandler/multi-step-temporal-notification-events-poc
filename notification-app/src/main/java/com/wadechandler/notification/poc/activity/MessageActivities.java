package com.wadechandler.notification.poc.activity;

import com.wadechandler.notification.poc.model.dto.MessageResult;
import io.temporal.activity.ActivityInterface;

import java.util.UUID;

@ActivityInterface
public interface MessageActivities {

    /**
     * Create a message for a resolved contact. The POST returns 202 Accepted.
     * Returns a {@link MessageResult} confirming the request was accepted.
     */
    MessageResult createMessage(UUID contactId, String templateId, String eventType);
}
