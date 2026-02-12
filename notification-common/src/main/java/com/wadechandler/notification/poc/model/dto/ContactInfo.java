package com.wadechandler.notification.poc.model.dto;

public record ContactInfo(
        String externalIdType,
        String externalIdValue,
        String email,
        String phone
) {}
