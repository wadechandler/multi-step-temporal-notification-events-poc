package com.wadechandler.notification.poc.command;

import com.wadechandler.notification.poc.config.KafkaTopics;
import com.wadechandler.notification.poc.model.Contact;
import com.wadechandler.notification.poc.model.dto.ContactRequest;
import com.wadechandler.notification.poc.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
@Profile("service")
@RequiredArgsConstructor
@Slf4j
public class ContactCommandHandler {

    private final ObjectMapper objectMapper;
    private final ContactRepository contactRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.CONTACT_COMMANDS_TOPIC)
    public void handle(String message) throws Exception {
        ContactRequest request = objectMapper.readValue(message, ContactRequest.class);

        // Idempotency: if a contact with this external ID already exists, skip insert
        // and re-publish the event for consistency (handles Kafka redelivery / Temporal retries)
        var existing = contactRepository.findByExternalIdTypeAndExternalIdValue(
                request.externalIdType(), request.externalIdValue());
        if (existing.isPresent()) {
            Contact existingContact = existing.get();
            log.info("Contact already exists for {}={} (id={}), idempotent skip",
                    request.externalIdType(), request.externalIdValue(), existingContact.getId());
            String contactJson = objectMapper.writeValueAsString(existingContact);
            kafkaTemplate.send(KafkaTopics.CONTACT_EVENTS_TOPIC, existingContact.getId().toString(), contactJson);
            return;
        }

        Contact contact = Contact.builder()
                .id(UUID.randomUUID())
                .externalIdType(request.externalIdType())
                .externalIdValue(request.externalIdValue())
                .email(request.email())
                .phone(request.phone())
                .status("ACTIVE")
                .build();

        contactRepository.save(contact);

        String contactJson = objectMapper.writeValueAsString(contact);
        kafkaTemplate.send(KafkaTopics.CONTACT_EVENTS_TOPIC, contact.getId().toString(), contactJson);

        log.info("Created contact {} for external ID {}={}",
                contact.getId(), contact.getExternalIdType(), contact.getExternalIdValue());
    }
}
