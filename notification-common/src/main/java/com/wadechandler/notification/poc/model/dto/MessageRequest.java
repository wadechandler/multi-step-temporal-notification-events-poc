package com.wadechandler.notification.poc.model.dto;

import java.util.UUID;

public record MessageRequest(
        UUID contactId,
        String templateId,
        String channel,
        String content
) {}
