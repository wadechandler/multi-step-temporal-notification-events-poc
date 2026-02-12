package com.wadechandler.notification.poc.worker;

import com.wadechandler.notification.poc.activity.ContactActivitiesImpl;
import com.wadechandler.notification.poc.activity.MessageActivitiesImpl;
import com.wadechandler.notification.poc.config.TaskQueues;
import com.wadechandler.notification.poc.workflow.NotificationWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Temporal worker factory and worker registration. Each @Profile maps to a
 * specific deployment mode:
 * <ul>
 *   <li>{@code wf-worker} — Combined mode (local dev). Registers workflow +
 *       contact activities + message activities in a single JVM, each on its
 *       own task queue.</li>
 *   <li>{@code wf-worker-orchestrator} — K8s wf-worker deployment. Workflow
 *       orchestration only on NOTIFICATION_QUEUE (no activities).</li>
 *   <li>{@code contact-wf-worker} — K8s contact-wf-worker deployment. Contact
 *       resolution activities only on CONTACT_ACTIVITY_QUEUE.</li>
 *   <li>{@code message-wf-worker} — K8s message-wf-worker deployment. Message
 *       creation activities only on MESSAGE_ACTIVITY_QUEUE.</li>
 * </ul>
 *
 * WorkflowClient and WorkflowServiceStubs are provided by
 * {@link TemporalClientConfig} (shared with ev-worker and all wf-worker profiles).
 */
@Configuration
public class TemporalWorkerConfig {

    /**
     * Combined mode: single JVM handles workflow + all activities on their respective queues.
     * Active when profile is "wf-worker" (local dev: service,ev-worker,wf-worker).
     */
    @Bean(initMethod = "start", destroyMethod = "shutdownNow")
    @Profile("wf-worker")
    public WorkerFactory combinedWorkerFactory(
            WorkflowClient workflowClient,
            ContactActivitiesImpl contactActivities,
            MessageActivitiesImpl messageActivities) {

        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);

        // Workflow worker (orchestration only, no activities)
        Worker workflowWorker = factory.newWorker(TaskQueues.NOTIFICATION_QUEUE);
        workflowWorker.registerWorkflowImplementationTypes(NotificationWorkflowImpl.class);

        // Contact activity worker
        Worker contactWorker = factory.newWorker(TaskQueues.CONTACT_ACTIVITY_QUEUE,
                WorkerOptions.newBuilder()
                        .setUsingVirtualThreadsOnActivityWorker(true)
                        .build());
        contactWorker.registerActivitiesImplementations(contactActivities);

        // Message activity worker
        Worker messageWorker = factory.newWorker(TaskQueues.MESSAGE_ACTIVITY_QUEUE,
                WorkerOptions.newBuilder()
                        .setUsingVirtualThreadsOnActivityWorker(true)
                        .build());
        messageWorker.registerActivitiesImplementations(messageActivities);

        return factory;
    }

    /**
     * Workflow-only worker: handles orchestration on NOTIFICATION_QUEUE.
     * Active in K8s split mode — the notification-wf-worker deployment.
     * No activities registered, no I/O — pure in-memory replay.
     */
    @Bean(initMethod = "start", destroyMethod = "shutdownNow")
    @Profile("wf-worker-orchestrator")
    public WorkerFactory workflowOnlyFactory(WorkflowClient workflowClient) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(TaskQueues.NOTIFICATION_QUEUE);
        worker.registerWorkflowImplementationTypes(NotificationWorkflowImpl.class);
        return factory;
    }

    /**
     * Contact activity worker: handles contact resolution on CONTACT_ACTIVITY_QUEUE.
     * Active in K8s split mode — the notification-contact-wf-worker deployment.
     */
    @Bean(initMethod = "start", destroyMethod = "shutdownNow")
    @Profile("contact-wf-worker")
    public WorkerFactory contactWorkerFactory(
            WorkflowClient workflowClient,
            ContactActivitiesImpl contactActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(TaskQueues.CONTACT_ACTIVITY_QUEUE,
                WorkerOptions.newBuilder()
                        .setUsingVirtualThreadsOnActivityWorker(true)
                        .build());
        worker.registerActivitiesImplementations(contactActivities);
        return factory;
    }

    /**
     * Message activity worker: handles message creation on MESSAGE_ACTIVITY_QUEUE.
     * Active in K8s split mode — the notification-message-wf-worker deployment.
     */
    @Bean(initMethod = "start", destroyMethod = "shutdownNow")
    @Profile("message-wf-worker")
    public WorkerFactory messageWorkerFactory(
            WorkflowClient workflowClient,
            MessageActivitiesImpl messageActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(TaskQueues.MESSAGE_ACTIVITY_QUEUE,
                WorkerOptions.newBuilder()
                        .setUsingVirtualThreadsOnActivityWorker(true)
                        .build());
        worker.registerActivitiesImplementations(messageActivities);
        return factory;
    }
}
