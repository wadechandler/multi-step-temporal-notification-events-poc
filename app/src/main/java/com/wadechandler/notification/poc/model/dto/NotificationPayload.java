package com.wadechandler.notification.poc.model.dto;

import java.util.List;

public record NotificationPayload(
        List<ContactInfo> contacts,
        String templateId
) {}
