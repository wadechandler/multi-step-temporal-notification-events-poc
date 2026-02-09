package com.wadechandler.notification.poc.repository;

import com.wadechandler.notification.poc.model.Contact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    Optional<Contact> findByExternalIdTypeAndExternalIdValue(String externalIdType, String externalIdValue);
}
