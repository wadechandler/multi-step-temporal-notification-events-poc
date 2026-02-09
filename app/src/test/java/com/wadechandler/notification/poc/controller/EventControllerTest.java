package com.wadechandler.notification.poc.controller;

import com.wadechandler.notification.poc.config.KafkaConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
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

        verify(kafkaTemplate).send(eq(KafkaConfig.NOTIFICATION_EVENTS_TOPIC), eq(eventId.toString()), anyString());
    }
}
