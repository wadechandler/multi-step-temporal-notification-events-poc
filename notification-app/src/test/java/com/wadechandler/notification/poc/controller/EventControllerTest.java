package com.wadechandler.notification.poc.controller;

import com.wadechandler.notification.poc.config.KafkaTopics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
@ActiveProfiles("service")
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void postEvent_shouldReturn202AndPublishToKafka() throws Exception {
        UUID eventId = UUID.randomUUID();
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "eventId": "%s",
                                    "eventType": "RxOrderNotification",
                                    "payload": {
                                        "orderId": "%s",
                                        "contacts": [
                                            {
                                                "externalIdType": "patientId",
                                                "externalIdValue": "ext-uuid-1",
                                                "email": "a@b.com"
                                            }
                                        ],
                                        "templateId": "rx-notification-v1"
                                    }
                                }
                                """.formatted(eventId, UUID.randomUUID())))
                .andExpect(status().isAccepted());

        ArgumentCaptor<Message<?>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(messageCaptor.capture());
        
        Message<?> capturedMessage = messageCaptor.getValue();
        assertThat(capturedMessage.getHeaders().get(KafkaHeaders.TOPIC)).isEqualTo(KafkaTopics.NOTIFICATION_EVENTS_TOPIC);
        assertThat(capturedMessage.getHeaders().get(KafkaHeaders.KEY)).isEqualTo(eventId.toString());
        assertThat(capturedMessage.getHeaders().get("X-Event-Type")).isEqualTo("RxOrderNotification");
    }

    @Test
    void postEvent_withNullEventType_shouldReturn400() throws Exception {
        UUID eventId = UUID.randomUUID();
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "eventId": "%s",
                                    "eventType": null,
                                    "payload": {
                                        "orderId": "%s"
                                    }
                                }
                                """.formatted(eventId, UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        verify(kafkaTemplate, never()).send(any(Message.class));
    }

    @Test
    void postEvent_withAbsentEventType_shouldReturn400() throws Exception {
        UUID eventId = UUID.randomUUID();
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "eventId": "%s",
                                    "payload": {
                                        "orderId": "%s"
                                    }
                                }
                                """.formatted(eventId, UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        verify(kafkaTemplate, never()).send(any(Message.class));
    }

    @Test
    void postEvent_withBlankEventType_shouldReturn400() throws Exception {
        UUID eventId = UUID.randomUUID();
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "eventId": "%s",
                                    "eventType": "   ",
                                    "payload": {
                                        "orderId": "%s"
                                    }
                                }
                                """.formatted(eventId, UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        verify(kafkaTemplate, never()).send(any(Message.class));
    }

    @Test
    void postEvent_shouldSetXEventTypeHeader() throws Exception {
        UUID eventId = UUID.randomUUID();
        String eventType = "RxOrderNotification";
        
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "eventId": "%s",
                                    "eventType": "%s",
                                    "payload": {
                                        "orderId": "%s"
                                    }
                                }
                                """.formatted(eventId, eventType, UUID.randomUUID())))
                .andExpect(status().isAccepted());

        ArgumentCaptor<Message<?>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(messageCaptor.capture());
        
        Message<?> capturedMessage = messageCaptor.getValue();
        assertThat(capturedMessage.getHeaders().get("X-Event-Type")).isEqualTo(eventType);
    }
}
