package com.wadechandler.notification.poc.activity;

import com.wadechandler.notification.poc.model.dto.MessageRequest;
import com.wadechandler.notification.poc.model.dto.MessageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Activity implementation that makes HTTP calls to the Message CQRS service.
 * This is a Spring-managed bean — RestClient is injected for HTTP communication.
 */
@Component
@Profile({"wf-worker", "message-wf-worker"})
@Slf4j
public class MessageActivitiesImpl implements MessageActivities {

    private final RestClient restClient;

    public MessageActivitiesImpl(
            RestClient.Builder restClientBuilder,
            @Value("${services.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public MessageResult createMessage(UUID contactId, String templateId, String eventType) {
        log.info("Creating message for contactId={}, template={}, eventType={}", contactId, templateId, eventType);
        var request = new MessageRequest(
                contactId,
                templateId,
                "EMAIL",
                "Notification for event type: " + eventType
        );
        restClient.post()
                .uri("/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
        log.info("Message create request accepted for contactId={}", contactId);
        // POST returns 202 with no body — return a result with what we know
        return new MessageResult(null, contactId, templateId, "ACCEPTED");
    }
}
