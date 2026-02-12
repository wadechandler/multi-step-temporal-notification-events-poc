package com.wadechandler.notification.poc.controller;

import com.wadechandler.notification.poc.config.KafkaTopics;
import com.wadechandler.notification.poc.model.Message;
import com.wadechandler.notification.poc.repository.MessageRepository;
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

@WebMvcTest(MessageController.class)
@ActiveProfiles("service")
class MessageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private MessageRepository messageRepository;

    @Test
    void postMessage_shouldReturn202AndPublishToKafka() throws Exception {
        UUID contactId = UUID.randomUUID();
        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "contactId": "%s",
                                    "templateId": "rx-notification-v1",
                                    "channel": "EMAIL",
                                    "content": "Your prescription is ready"
                                }
                                """.formatted(contactId)))
                .andExpect(status().isAccepted());

        verify(kafkaTemplate).send(eq(KafkaTopics.MESSAGE_COMMANDS_TOPIC), anyString(), anyString());
    }

    @Test
    void getMessageById_whenFound_shouldReturn200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        Message message = Message.builder()
                .id(id)
                .contactId(contactId)
                .templateId("rx-notification-v1")
                .channel("EMAIL")
                .content("Your prescription is ready")
                .status("PENDING")
                .createdAt(Instant.now())
                .build();
        when(messageRepository.findById(id)).thenReturn(Optional.of(message));

        mockMvc.perform(get("/messages/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.contactId").value(contactId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getMessageById_whenNotFound_shouldReturn404() throws Exception {
        UUID id = UUID.randomUUID();
        when(messageRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/messages/{id}", id))
                .andExpect(status().isNotFound());
    }
}
