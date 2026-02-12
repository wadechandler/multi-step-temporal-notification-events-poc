package com.wadechandler.notification.poc.command;

import com.wadechandler.notification.poc.config.KafkaTopics;
import com.wadechandler.notification.poc.model.Message;
import com.wadechandler.notification.poc.model.dto.MessageRequest;
import com.wadechandler.notification.poc.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageCommandHandlerTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private MessageCommandHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        handler = new MessageCommandHandler(objectMapper, messageRepository, kafkaTemplate);
    }

    @Test
    void handle_shouldSaveMessageAndPublishEvent() throws Exception {
        UUID contactId = UUID.randomUUID();
        MessageRequest request = new MessageRequest(contactId, "rx-notification-v1", "EMAIL", "Your prescription is ready");
        String json = objectMapper.writeValueAsString(request);

        when(messageRepository.save(org.mockito.ArgumentMatchers.any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        handler.handle(json);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());

        Message saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getContactId()).isEqualTo(contactId);
        assertThat(saved.getTemplateId()).isEqualTo("rx-notification-v1");
        assertThat(saved.getChannel()).isEqualTo("EMAIL");
        assertThat(saved.getContent()).isEqualTo("Your prescription is ready");
        assertThat(saved.getStatus()).isEqualTo("PENDING");

        verify(kafkaTemplate).send(
                eq(KafkaTopics.MESSAGE_EVENTS_TOPIC),
                eq(saved.getId().toString()),
                anyString()
        );
    }

    @Test
    void handle_shouldDefaultChannelToEmail_whenNotSpecified() throws Exception {
        UUID contactId = UUID.randomUUID();
        MessageRequest request = new MessageRequest(contactId, "template-1", null, "Test content");
        String json = objectMapper.writeValueAsString(request);

        when(messageRepository.save(org.mockito.ArgumentMatchers.any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        handler.handle(json);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());

        assertThat(captor.getValue().getChannel()).isEqualTo("EMAIL");
    }
}
