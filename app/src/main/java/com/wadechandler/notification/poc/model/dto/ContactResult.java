package com.wadechandler.notification.poc.model.dto;

import java.util.UUID;

public record ContactResult(
        UUID id,
        String externalIdType,
        String externalIdValue,
        String email,
        String phone,
        String status
) {}
