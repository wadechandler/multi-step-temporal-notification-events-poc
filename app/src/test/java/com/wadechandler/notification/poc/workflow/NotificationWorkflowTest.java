package com.wadechandler.notification.poc.workflow;

import com.wadechandler.notification.poc.activity.ContactActivities;
import com.wadechandler.notification.poc.activity.MessageActivities;
import com.wadechandler.notification.poc.model.dto.ContactInfo;
import com.wadechandler.notification.poc.model.dto.ContactResult;
import com.wadechandler.notification.poc.model.dto.MessageResult;
import com.wadechandler.notification.poc.model.dto.NotificationPayload;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationWorkflowTest {

    private static final String TASK_QUEUE = "NOTIFICATION_QUEUE";

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient workflowClient;
    private ContactActivities contactActivities;
    private MessageActivities messageActivities;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(NotificationWorkflowImpl.class);

        contactActivities = mock(ContactActivities.class);
        messageActivities = mock(MessageActivities.class);
        worker.registerActivitiesImplementations(contactActivities, messageActivities);

        workflowClient = testEnv.getWorkflowClient();
        testEnv.start();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void happyPath_contactExists_messageCreated() {
        UUID eventId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();

        ContactResult contactResult = new ContactResult(
                contactId, "patientId", "ext-1", "test@example.com", "+1234567890", "ACTIVE");
        when(contactActivities.getContact("patientId", "ext-1"))
                .thenReturn(Optional.of(contactResult));
        when(messageActivities.createMessage(contactId, "rx-v1", "RxOrderNotification"))
                .thenReturn(new MessageResult(null, contactId, "rx-v1", "ACCEPTED"));

        NotificationPayload payload = new NotificationPayload(
                List.of(new ContactInfo("patientId", "ext-1", "test@example.com", "+1234567890")),
                "rx-v1");

        NotificationWorkflow workflow = workflowClient.newWorkflowStub(
                NotificationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("test-happy-" + eventId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());

        workflow.processNotification(eventId, "RxOrderNotification", payload);

        verify(contactActivities).getContact("patientId", "ext-1");
        verify(contactActivities, never()).createContact(any());
        verify(contactActivities, never()).pollForContact(anyString(), anyString());
        verify(messageActivities).createMessage(contactId, "rx-v1", "RxOrderNotification");
    }

    @Test
    void createPath_contactNotFound_createAndPoll_messageCreated() {
        UUID eventId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();

        // getContact returns empty (contact doesn't exist yet)
        when(contactActivities.getContact("patientId", "ext-1"))
                .thenReturn(Optional.empty());

        // pollForContact returns the materialized contact
        ContactResult contactResult = new ContactResult(
                contactId, "patientId", "ext-1", "test@example.com", "+1234567890", "ACTIVE");
        when(contactActivities.pollForContact("patientId", "ext-1"))
                .thenReturn(contactResult);

        when(messageActivities.createMessage(contactId, "rx-v1", "RxOrderNotification"))
                .thenReturn(new MessageResult(null, contactId, "rx-v1", "ACCEPTED"));

        NotificationPayload payload = new NotificationPayload(
                List.of(new ContactInfo("patientId", "ext-1", "test@example.com", "+1234567890")),
                "rx-v1");

        NotificationWorkflow workflow = workflowClient.newWorkflowStub(
                NotificationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("test-create-" + eventId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());

        workflow.processNotification(eventId, "RxOrderNotification", payload);

        verify(contactActivities).getContact("patientId", "ext-1");
        verify(contactActivities).createContact(any(ContactInfo.class));
        verify(contactActivities).pollForContact("patientId", "ext-1");
        verify(messageActivities).createMessage(contactId, "rx-v1", "RxOrderNotification");
    }

    @Test
    void multipleContacts_processedInParallel() {
        UUID eventId = UUID.randomUUID();
        UUID contactId1 = UUID.randomUUID();
        UUID contactId2 = UUID.randomUUID();

        ContactResult result1 = new ContactResult(
                contactId1, "patientId", "ext-1", "a@b.com", null, "ACTIVE");
        ContactResult result2 = new ContactResult(
                contactId2, "patientId", "ext-2", "c@d.com", null, "ACTIVE");

        when(contactActivities.getContact("patientId", "ext-1"))
                .thenReturn(Optional.of(result1));
        when(contactActivities.getContact("patientId", "ext-2"))
                .thenReturn(Optional.of(result2));
        when(messageActivities.createMessage(eq(contactId1), eq("rx-v1"), eq("RxOrderNotification")))
                .thenReturn(new MessageResult(null, contactId1, "rx-v1", "ACCEPTED"));
        when(messageActivities.createMessage(eq(contactId2), eq("rx-v1"), eq("RxOrderNotification")))
                .thenReturn(new MessageResult(null, contactId2, "rx-v1", "ACCEPTED"));

        NotificationPayload payload = new NotificationPayload(
                List.of(
                        new ContactInfo("patientId", "ext-1", "a@b.com", null),
                        new ContactInfo("patientId", "ext-2", "c@d.com", null)),
                "rx-v1");

        NotificationWorkflow workflow = workflowClient.newWorkflowStub(
                NotificationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("test-multi-" + eventId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());

        workflow.processNotification(eventId, "RxOrderNotification", payload);

        // Both contacts were looked up
        verify(contactActivities).getContact("patientId", "ext-1");
        verify(contactActivities).getContact("patientId", "ext-2");
        // Messages created for both
        verify(messageActivities).createMessage(contactId1, "rx-v1", "RxOrderNotification");
        verify(messageActivities).createMessage(contactId2, "rx-v1", "RxOrderNotification");
    }
}
