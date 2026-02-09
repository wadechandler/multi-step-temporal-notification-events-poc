package com.wadechandler.notification.poc.controller;

import com.wadechandler.notification.poc.config.KafkaConfig;
import com.wadechandler.notification.poc.model.dto.ExternalEventRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @PostMapping
    public ResponseEntity<Void> createEvent(@RequestBody ExternalEventRequest request) throws Exception {
        String json = objectMapper.writeValueAsString(request);
        kafkaTemplate.send(KafkaConfig.NOTIFICATION_EVENTS_TOPIC, request.eventId().toString(), json);
        log.info("Published external event {} of type {}", request.eventId(), request.eventType());
        return ResponseEntity.accepted().build();
    }
}
