package com.wadechandler.notification.poc.controller;

import com.wadechandler.notification.poc.config.KafkaTopics;
import com.wadechandler.notification.poc.model.Message;
import com.wadechandler.notification.poc.model.dto.MessageRequest;
import com.wadechandler.notification.poc.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@RestController
@Profile("service")
@RequestMapping("/messages")
@RequiredArgsConstructor
@Slf4j
public class MessageController {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MessageRepository messageRepository;

    @PostMapping
    public ResponseEntity<Void> createMessage(@RequestBody MessageRequest request) throws Exception {
        String json = objectMapper.writeValueAsString(request);
        String key = request.contactId().toString();
        kafkaTemplate.send(KafkaTopics.MESSAGE_COMMANDS_TOPIC, key, json);
        log.info("Published MessageCreateRequested for contactId={}", request.contactId());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Message> getMessage(@PathVariable UUID id) {
        return messageRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
