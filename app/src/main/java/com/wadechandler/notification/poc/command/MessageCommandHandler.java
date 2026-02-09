package com.wadechandler.notification.poc.command;

import com.wadechandler.notification.poc.config.KafkaConfig;
import com.wadechandler.notification.poc.model.Message;
import com.wadechandler.notification.poc.model.dto.MessageRequest;
import com.wadechandler.notification.poc.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class MessageCommandHandler {

    private final ObjectMapper objectMapper;
    private final MessageRepository messageRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @KafkaListener(topics = KafkaConfig.MESSAGE_COMMANDS_TOPIC)
    public void handle(String message) throws Exception {
        MessageRequest request = objectMapper.readValue(message, MessageRequest.class);

        Message msg = Message.builder()
                .id(UUID.randomUUID())
                .contactId(request.contactId())
                .templateId(request.templateId())
                .channel(request.channel() != null ? request.channel() : "EMAIL")
                .content(request.content())
                .status("PENDING")
                .build();

        messageRepository.save(msg);

        String msgJson = objectMapper.writeValueAsString(msg);
        kafkaTemplate.send(KafkaConfig.MESSAGE_EVENTS_TOPIC, msg.getId().toString(), msgJson);

        log.info("Created message {} for contact {}", msg.getId(), msg.getContactId());
    }
}
