package com.wadechandler.notification.poc.controller;

import com.wadechandler.notification.poc.config.KafkaTopics;
import com.wadechandler.notification.poc.model.Contact;
import com.wadechandler.notification.poc.repository.ContactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContactController.class)
@ActiveProfiles("service")
class ContactControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private ContactRepository contactRepository;

    @Test
    void postContact_shouldReturn202AndPublishToKafka() throws Exception {
        mockMvc.perform(post("/contacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "externalIdType": "patientId",
                                    "externalIdValue": "ext-123",
                                    "email": "test@example.com",
                                    "phone": "+1234567890"
                                }
                                """))
                .andExpect(status().isAccepted());

        verify(kafkaTemplate).send(eq(KafkaTopics.CONTACT_COMMANDS_TOPIC), anyString(), anyString());
    }

    @Test
    void getContactById_whenFound_shouldReturn200() throws Exception {
        UUID id = UUID.randomUUID();
        Contact contact = Contact.builder()
                .id(id)
                .externalIdType("patientId")
                .externalIdValue("ext-123")
                .email("test@example.com")
                .phone("+1234567890")
                .status("ACTIVE")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(contactRepository.findById(id)).thenReturn(Optional.of(contact));

        mockMvc.perform(get("/contacts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getContactById_whenNotFound_shouldReturn404() throws Exception {
        UUID id = UUID.randomUUID();
        when(contactRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/contacts/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void getContactByExternalId_whenFound_shouldReturn200() throws Exception {
        Contact contact = Contact.builder()
                .id(UUID.randomUUID())
                .externalIdType("patientId")
                .externalIdValue("ext-456")
                .email("found@example.com")
                .status("ACTIVE")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(contactRepository.findByExternalIdTypeAndExternalIdValue("patientId", "ext-456"))
                .thenReturn(Optional.of(contact));

        mockMvc.perform(get("/contacts")
                        .param("externalIdType", "patientId")
                        .param("externalIdValue", "ext-456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("found@example.com"));
    }

    @Test
    void getContactByExternalId_whenNotFound_shouldReturn404() throws Exception {
        when(contactRepository.findByExternalIdTypeAndExternalIdValue("patientId", "no-such"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/contacts")
                        .param("externalIdType", "patientId")
                        .param("externalIdValue", "no-such"))
                .andExpect(status().isNotFound());
    }
}
