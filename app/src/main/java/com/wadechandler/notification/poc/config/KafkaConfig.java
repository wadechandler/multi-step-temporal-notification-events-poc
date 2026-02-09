package com.wadechandler.notification.poc.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String NOTIFICATION_EVENTS_TOPIC = "notification-events";
    public static final String CONTACT_COMMANDS_TOPIC = "contact-commands";
    public static final String CONTACT_EVENTS_TOPIC = "contact-events";
    public static final String MESSAGE_COMMANDS_TOPIC = "message-commands";
    public static final String MESSAGE_EVENTS_TOPIC = "message-events";

    @Bean
    public NewTopic notificationEventsTopic() {
        return TopicBuilder.name(NOTIFICATION_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic contactCommandsTopic() {
        return TopicBuilder.name(CONTACT_COMMANDS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic contactEventsTopic() {
        return TopicBuilder.name(CONTACT_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic messageCommandsTopic() {
        return TopicBuilder.name(MESSAGE_COMMANDS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic messageEventsTopic() {
        return TopicBuilder.name(MESSAGE_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }
}
