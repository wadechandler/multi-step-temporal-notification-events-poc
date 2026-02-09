package com.wadechandler.notification.poc.worker;

import com.wadechandler.notification.poc.activity.ContactActivitiesImpl;
import com.wadechandler.notification.poc.activity.MessageActivitiesImpl;
import com.wadechandler.notification.poc.workflow.NotificationWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manual Temporal bean wiring. The temporal-spring-boot-starter may not auto-configure
 * with Spring Boot 4, so we configure WorkflowServiceStubs, WorkflowClient, WorkerFactory,
 * and the Worker explicitly.
 */
@Configuration
public class TemporalWorkerConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(
            @Value("${temporal.connection.target}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(target)
                        .build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs) {
        return WorkflowClient.newInstance(serviceStubs);
    }

    @Bean(initMethod = "start", destroyMethod = "shutdownNow")
    public WorkerFactory workerFactory(
            WorkflowClient workflowClient,
            @Value("${temporal.worker.task-queue}") String taskQueue,
            ContactActivitiesImpl contactActivities,
            MessageActivitiesImpl messageActivities) {

        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);

        Worker worker = factory.newWorker(taskQueue,
                WorkerOptions.newBuilder()
                        .setUsingVirtualThreadsOnActivityWorker(true)
                        .build());

        worker.registerWorkflowImplementationTypes(NotificationWorkflowImpl.class);
        worker.registerActivitiesImplementations(contactActivities, messageActivities);

        return factory;
    }
}
