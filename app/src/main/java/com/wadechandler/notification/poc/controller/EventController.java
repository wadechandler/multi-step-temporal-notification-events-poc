package com.wadechandler.notification.poc.controller;

import com.wadechandler.notification.poc.config.KafkaConfig;
import com.wadechandler.notification.poc.model.dto.ExternalEventRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
        // Production: validate request.payload() against JSON Schema for this eventType.
        // See the schema-driven ingestion pattern in the project design docs.
        if (!StringUtils.hasText(request.eventType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventType is required and must not be blank");
        }

        String json = objectMapper.writeValueAsString(request);
        var message = MessageBuilder
                .withPayload(json)
                .setHeader(KafkaHeaders.TOPIC, KafkaConfig.NOTIFICATION_EVENTS_TOPIC)
                .setHeader(KafkaHeaders.KEY, request.eventId().toString())
                .setHeader("X-Event-Type", request.eventType())
                .build();

        kafkaTemplate.send(message);
        log.info("Published external event {} of type {}", request.eventId(), request.eventType());
        return ResponseEntity.accepted().build();
    }
}
