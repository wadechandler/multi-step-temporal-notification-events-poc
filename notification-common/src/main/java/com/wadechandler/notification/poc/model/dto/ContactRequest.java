package com.wadechandler.notification.poc.model.dto;

public record ContactRequest(
        String externalIdType,
        String externalIdValue,
        String email,
        String phone
) {}
