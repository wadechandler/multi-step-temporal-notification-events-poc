package com.wadechandler.notification.poc.activity;

import com.wadechandler.notification.poc.model.dto.ContactInfo;
import com.wadechandler.notification.poc.model.dto.ContactRequest;
import com.wadechandler.notification.poc.model.dto.ContactResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Activity implementation that makes HTTP calls to the Contact CQRS service.
 * This is a Spring-managed bean — RestClient is injected for HTTP communication.
 */
@Component
@Profile({"wf-worker", "contact-wf-worker"})
@Slf4j
public class ContactActivitiesImpl implements ContactActivities {

    private final RestClient restClient;

    public ContactActivitiesImpl(
            RestClient.Builder restClientBuilder,
            @Value("${services.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public Optional<ContactResult> getContact(String externalIdType, String externalIdValue) {
        log.info("Looking up contact: {}={}", externalIdType, externalIdValue);
        try {
            ContactResult result = restClient.get()
                    .uri("/contacts?externalIdType={type}&externalIdValue={value}",
                            externalIdType, externalIdValue)
                    .retrieve()
                    .body(ContactResult.class);
            log.info("Found contact: {}={} -> id={}", externalIdType, externalIdValue,
                    result != null ? result.id() : "null");
            return Optional.ofNullable(result);
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Contact not found: {}={}", externalIdType, externalIdValue);
            return Optional.empty();
        }
    }

    @Override
    public void createContact(ContactInfo contactInfo) {
        log.info("Creating contact: {}={}", contactInfo.externalIdType(), contactInfo.externalIdValue());
        var request = new ContactRequest(
                contactInfo.externalIdType(),
                contactInfo.externalIdValue(),
                contactInfo.email(),
                contactInfo.phone()
        );
        restClient.post()
                .uri("/contacts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
        log.info("Contact create request accepted for {}={}",
                contactInfo.externalIdType(), contactInfo.externalIdValue());
    }

    @Override
    public ContactResult pollForContact(String externalIdType, String externalIdValue) {
        log.info("Polling for contact: {}={}", externalIdType, externalIdValue);
        try {
            ContactResult result = restClient.get()
                    .uri("/contacts?externalIdType={type}&externalIdValue={value}",
                            externalIdType, externalIdValue)
                    .retrieve()
                    .body(ContactResult.class);
            log.info("Contact materialized: {}={} -> id={}", externalIdType, externalIdValue,
                    result != null ? result.id() : "null");
            return result;
        } catch (HttpClientErrorException.NotFound e) {
            // 404 in polling means "not yet consistent" — throw so Temporal retries
            throw new ContactNotFoundException(externalIdType, externalIdValue);
        }
    }
}
