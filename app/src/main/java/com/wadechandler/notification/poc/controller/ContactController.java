package com.wadechandler.notification.poc.controller;

import com.wadechandler.notification.poc.config.KafkaConfig;
import com.wadechandler.notification.poc.model.Contact;
import com.wadechandler.notification.poc.model.dto.ContactRequest;
import com.wadechandler.notification.poc.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@RestController
@RequestMapping("/contacts")
@RequiredArgsConstructor
@Slf4j
public class ContactController {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ContactRepository contactRepository;

    @PostMapping
    public ResponseEntity<Void> createContact(@RequestBody ContactRequest request) throws Exception {
        String json = objectMapper.writeValueAsString(request);
        String key = request.externalIdType() + "-" + request.externalIdValue();
        kafkaTemplate.send(KafkaConfig.CONTACT_COMMANDS_TOPIC, key, json);
        log.info("Published ContactCreateRequested for {}={}", request.externalIdType(), request.externalIdValue());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Contact> getContact(@PathVariable UUID id) {
        return contactRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<Contact> getContactByExternalId(
            @RequestParam String externalIdType,
            @RequestParam String externalIdValue) {
        return contactRepository.findByExternalIdTypeAndExternalIdValue(externalIdType, externalIdValue)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
