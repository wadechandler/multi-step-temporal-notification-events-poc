package com.wadechandler.notification.poc.config;

/**
 * Kafka topic name constants shared across all modules.
 * Extracted from the original KafkaConfig to avoid coupling library modules
 * to Spring Kafka's {@code NewTopic} bean definitions.
 */
public final class KafkaTopics {

    public static final String NOTIFICATION_EVENTS_TOPIC = "notification-events";
    public static final String CONTACT_COMMANDS_TOPIC = "contact-commands";
    public static final String CONTACT_EVENTS_TOPIC = "contact-events";
    public static final String MESSAGE_COMMANDS_TOPIC = "message-commands";
    public static final String MESSAGE_EVENTS_TOPIC = "message-events";

    private KafkaTopics() {
        // constants only
    }
}
