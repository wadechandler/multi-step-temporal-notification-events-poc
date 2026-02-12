package com.wadechandler.notification.poc.model.dto;

import java.util.UUID;

public record MessageResult(
        UUID id,
        UUID contactId,
        String templateId,
        String status
) {}
