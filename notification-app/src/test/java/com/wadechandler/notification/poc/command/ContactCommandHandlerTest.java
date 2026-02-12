package com.wadechandler.notification.poc.command;

import com.wadechandler.notification.poc.config.KafkaTopics;
import com.wadechandler.notification.poc.model.Contact;
import com.wadechandler.notification.poc.model.dto.ContactRequest;
import com.wadechandler.notification.poc.repository.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactCommandHandlerTest {

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private ContactCommandHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        handler = new ContactCommandHandler(objectMapper, contactRepository, kafkaTemplate);
    }

    @Test
    void handle_shouldSaveContactAndPublishEvent() throws Exception {
        ContactRequest request = new ContactRequest("patientId", "ext-123", "test@example.com", "+1234567890");
        String json = objectMapper.writeValueAsString(request);

        when(contactRepository.findByExternalIdTypeAndExternalIdValue("patientId", "ext-123"))
                .thenReturn(Optional.empty());
        when(contactRepository.save(any(Contact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        handler.handle(json);

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(contactRepository).save(captor.capture());

        Contact saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getExternalIdType()).isEqualTo("patientId");
        assertThat(saved.getExternalIdValue()).isEqualTo("ext-123");
        assertThat(saved.getEmail()).isEqualTo("test@example.com");
        assertThat(saved.getPhone()).isEqualTo("+1234567890");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");

        verify(kafkaTemplate).send(
                eq(KafkaTopics.CONTACT_EVENTS_TOPIC),
                eq(saved.getId().toString()),
                anyString()
        );
    }

    @Test
    void handle_shouldGenerateUniqueId() throws Exception {
        ContactRequest request = new ContactRequest("patientId", "ext-456", "a@b.com", null);
        String json = objectMapper.writeValueAsString(request);

        when(contactRepository.findByExternalIdTypeAndExternalIdValue("patientId", "ext-456"))
                .thenReturn(Optional.empty());
        when(contactRepository.save(any(Contact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        handler.handle(json);

        ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
        verify(contactRepository).save(captor.capture());

        assertThat(captor.getValue().getId()).isNotNull();
        assertThat(captor.getValue().getEmail()).isEqualTo("a@b.com");
        assertThat(captor.getValue().getPhone()).isNull();
    }

    @Test
    void handle_shouldSkipInsertWhenContactAlreadyExists() throws Exception {
        ContactRequest request = new ContactRequest("patientId", "ext-123", "test@example.com", "+1234567890");
        String json = objectMapper.writeValueAsString(request);

        UUID existingId = UUID.randomUUID();
        Contact existingContact = Contact.builder()
                .id(existingId)
                .externalIdType("patientId")
                .externalIdValue("ext-123")
                .email("test@example.com")
                .phone("+1234567890")
                .status("ACTIVE")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(contactRepository.findByExternalIdTypeAndExternalIdValue("patientId", "ext-123"))
                .thenReturn(Optional.of(existingContact));

        handler.handle(json);

        // Should NOT save a new contact
        verify(contactRepository, never()).save(any(Contact.class));

        // Should still publish the event for the existing contact (consistency)
        verify(kafkaTemplate).send(
                eq(KafkaTopics.CONTACT_EVENTS_TOPIC),
                eq(existingId.toString()),
                anyString()
        );
    }
}
