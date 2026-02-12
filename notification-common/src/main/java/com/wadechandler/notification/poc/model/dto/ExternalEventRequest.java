package com.wadechandler.notification.poc.model.dto;

import java.util.Map;
import java.util.UUID;

public record ExternalEventRequest(
        UUID eventId,
        String eventType,
        Map<String, Object> payload
) {}
