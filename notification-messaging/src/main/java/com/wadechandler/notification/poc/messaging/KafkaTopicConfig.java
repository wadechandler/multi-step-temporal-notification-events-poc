package com.wadechandler.notification.poc.messaging;

import com.wadechandler.notification.poc.config.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic auto-creation beans. These ensure the required topics exist
 * when the application starts and a Kafka AdminClient is available.
 * <p>
 * Only active for profiles that interact with Kafka. The wf-worker profile
 * communicates via REST (not Kafka), so it should not attempt topic creation.
 */
@Configuration
@Profile({"service", "ev-worker"})
public class KafkaTopicConfig {

    @Bean
    public NewTopic notificationEventsTopic() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATION_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic contactCommandsTopic() {
        return TopicBuilder.name(KafkaTopics.CONTACT_COMMANDS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic contactEventsTopic() {
        return TopicBuilder.name(KafkaTopics.CONTACT_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic messageCommandsTopic() {
        return TopicBuilder.name(KafkaTopics.MESSAGE_COMMANDS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic messageEventsTopic() {
        return TopicBuilder.name(KafkaTopics.MESSAGE_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }
}
