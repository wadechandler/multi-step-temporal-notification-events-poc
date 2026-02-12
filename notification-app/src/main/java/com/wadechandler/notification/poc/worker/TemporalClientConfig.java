package com.wadechandler.notification.poc.worker;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Temporal client beans shared by ev-worker (to start workflows) and wf-worker
 * (to register workers). Separated from {@link TemporalWorkerConfig} so that
 * the ev-worker profile gets a WorkflowClient without pulling in the full
 * worker factory.
 */
@Configuration
@Profile({"ev-worker", "wf-worker", "wf-worker-orchestrator", "contact-wf-worker", "message-wf-worker"})
public class TemporalClientConfig {

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
}
