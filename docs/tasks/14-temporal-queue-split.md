# Task 14: Temporal Task Queue Splitting + Workflow Restructure

## Context

### The problem

Currently, all Temporal work runs on a single task queue (`NOTIFICATION_QUEUE`): the workflow
orchestration, contact resolution activities, and message creation activities. A single
`notification-wf-worker` deployment handles everything, and scaling is all-or-nothing.

Contact resolution can be **slow** — creating a new contact and polling for eventual consistency
takes seconds with retries. Message creation is **fast** — fire-and-forget POST. If a batch of
new contacts arrives, the slow contact polling ties up activity worker slots and the workflow
scheduler itself. Other notifications that are ready for message creation can't proceed because
the shared worker pool is saturated with slow contact polling.

### The domain model

This matters because of how the notification processing domain works:

- An **RxOrderNotification** event references 1+ contacts (patients) tied to the same order.
- **Contact resolution** must happen first: look up each contact, create any that don't exist
  (rare — new patients), wait for eventual consistency. Most contacts already exist (returning
  patients with existing prescriptions), so this is usually fast.
- **Message creation** depends on the full set of resolved contacts because:
  - Contacts sharing the same phone or email may get a **bundled message** (one message per
    unique endpoint instead of one per contact).
  - In production, contact **preferences** (voice, SMS, email) determine which message channels
    to use. A patient might get voice + email, or just SMS.
- Therefore: resolve ALL contacts first, then bundle and create messages.

### The solution

Split activities onto **separate task queues** so they can be scaled independently:

- `NOTIFICATION_QUEUE` — workflow orchestration only (lightweight scheduling decisions, no I/O)
- `CONTACT_ACTIVITY_QUEUE` — contact resolution activities (getContact, createContact, pollForContact)
- `MESSAGE_ACTIVITY_QUEUE` — message creation activities (bundling + createMessage)

Each queue gets its own Kubernetes deployment and KEDA ScaledObject:

- `notification-wf-worker` — polls `NOTIFICATION_QUEUE` for workflow tasks
- `notification-contact-wf-worker` — polls `CONTACT_ACTIVITY_QUEUE` for contact activities
- `notification-message-wf-worker` — polls `MESSAGE_ACTIVITY_QUEUE` for message activities

### Why 3 queues, not 2?

Workflow tasks are **pure in-memory replay** — no I/O, microseconds per task. If they share a
queue with contact activities, slow `pollForContact` retries starve the scheduler. Notification B
can't even decide "my contacts are resolved, go create messages" because the workers are
saturated with Notification A's polling. Keeping the workflow queue isolated ensures scheduling
decisions are never blocked by I/O.

### Scaling benefit is across notifications

Within a single notification, the two phases are sequential (resolve contacts, then create
messages). The scaling benefit comes across many concurrent notifications:

- **Contact activity workers** are busy with Notification 1's slow contact creation.
- **Message activity workers** can simultaneously handle Notification 2's message creation.
- **Workflow workers** can schedule tasks for both without being blocked by either.
- KEDA scales each pool independently based on its queue depth.

### Deployment modes

**Kubernetes (default, production-like):** The Helm chart always deploys 3 wf-worker
Deployments. This is the mode you demo, test, and scale. No feature flag needed.

**Local dev (`bootRun`):** Run with `--spring.profiles.active=service,ev-worker,wf-worker`.
The `wf-worker` profile registers all 3 workers (workflow + contact activities + message
activities) in a single JVM when the split profiles are not active. This preserves the
existing local dev experience.

## Prerequisites
- Task 10 complete (multi-module structure with Spring profiles)
- Task 12 complete (Helm chart with wf-worker deployment and KEDA ScaledObject)

### Important: Codebase conventions established in Task 12

Read the actual codebase before implementing. These conventions were established during
Task 12 and differ from what earlier task documents may have described:

1. **Spring Boot 4 autoconfig class names.** Use the SB4 package names in profile YAMLs:
   - `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`
   - `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration`
   - `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`
   - `org.springframework.boot.data.jpa.autoconfigure.JpaRepositoriesAutoConfiguration`

2. **`TemporalClientConfig` is separate from `TemporalWorkerConfig`.** `TemporalClientConfig`
   (`@Profile({"ev-worker", "wf-worker"})`) provides `WorkflowServiceStubs` and
   `WorkflowClient` beans shared by ev-worker and all wf-worker profiles. **Do not merge
   these classes.** Only modify `TemporalWorkerConfig` for worker registration. Update
   `TemporalClientConfig`'s `@Profile` to include the new profiles.

3. **Virtual threads API.** Use `WorkerOptions.newBuilder().setUsingVirtualThreadsOnActivityWorker(true)`,
   not `setActivityExecutor(Executors.newVirtualThreadPerTaskExecutor())`.

4. **KEDA ScaledObjects use typed trigger pattern.** Infrastructure addresses (`temporal.address`,
   `temporal.namespace`) come from top-level values, not hardcoded in trigger metadata. Follow
   the existing `templates/wf-worker/scaled-object.yaml` pattern.

5. **ConfigMaps use Helm helpers.** `SERVICES_BASE_URL` comes from the
   `notification-poc.serviceUrl` helper. `TEMPORAL_ADDRESS` and queue names come from
   top-level values.

6. **Injectable configuration.** Consumer group IDs, database name, service URLs are all
   injectable via env vars in ConfigMaps. New ConfigMaps should follow the same pattern.

## Key Files to Read
- `notification-app/src/main/java/.../workflow/NotificationWorkflowImpl.java` — Current workflow
- `notification-app/src/main/java/.../workflow/NotificationWorkflow.java` — Workflow interface
- `notification-app/src/main/java/.../activity/ContactActivities.java` — Contact activities interface
- `notification-app/src/main/java/.../activity/MessageActivities.java` — Message activities interface
- `notification-app/src/main/java/.../activity/ContactActivitiesImpl.java` — Implementation
- `notification-app/src/main/java/.../activity/MessageActivitiesImpl.java` — Implementation
- `notification-app/src/main/java/.../worker/TemporalWorkerConfig.java` — Current worker config
- `notification-app/src/main/java/.../worker/TemporalClientConfig.java` — Temporal client beans
- `notification-app/src/main/resources/application-wf-worker.yml` — Worker profile config
- `notification-common/src/main/java/.../model/dto/ContactResult.java` — Contact DTO (has email, phone)
- `charts/notification-poc/values.yaml` — Helm values
- `charts/notification-poc/templates/wf-worker/` — Existing wf-worker templates
- `charts/notification-poc/templates/_helpers.tpl` — Helm helpers (baseTriggers, serviceUrl)
- `.cursor/rules/20-temporal-workflows.mdc` — Temporal patterns

## Deliverables

### 1. Define Task Queue Constants

Create `notification-common/src/main/java/com/wadechandler/notification/poc/config/TaskQueues.java`:

```java
package com.wadechandler.notification.poc.config;

public final class TaskQueues {
    public static final String NOTIFICATION_QUEUE = "NOTIFICATION_QUEUE";
    public static final String CONTACT_ACTIVITY_QUEUE = "CONTACT_ACTIVITY_QUEUE";
    public static final String MESSAGE_ACTIVITY_QUEUE = "MESSAGE_ACTIVITY_QUEUE";

    private TaskQueues() {}
}
```

### 2. Update NotificationWorkflowImpl — Per-Activity Task Queues + Bundling

The workflow creates activity stubs that route to specific task queues and adds basic
message bundling in Phase 2:

```java
public class NotificationWorkflowImpl implements NotificationWorkflow {

    private static final Logger log = Workflow.getLogger(NotificationWorkflowImpl.class);

    // Contact activities route to CONTACT_ACTIVITY_QUEUE
    private final ContactActivities contactActivities = Workflow.newActivityStub(
            ContactActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.CONTACT_ACTIVITY_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    // Polling stub — same queue, aggressive retries
    private final ContactActivities pollingContactActivities = Workflow.newActivityStub(
            ContactActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.CONTACT_ACTIVITY_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(10)
                            .build())
                    .build());

    // Message activities route to MESSAGE_ACTIVITY_QUEUE
    private final MessageActivities messageActivities = Workflow.newActivityStub(
            MessageActivities.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(TaskQueues.MESSAGE_ACTIVITY_QUEUE)
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    @Override
    public void processNotification(UUID eventId, String eventType, NotificationPayload payload) {
        log.info("Processing notification event: {} type: {}", eventId, eventType);

        // --- Phase 1: Resolve all contacts in parallel (CONTACT_ACTIVITY_QUEUE) ---
        List<Promise<ContactResult>> contactPromises = payload.contacts().stream()
                .map(contact -> Async.function(() -> resolveContact(contact)))
                .toList();
        Promise.allOf(contactPromises).get();

        List<ContactResult> resolvedContacts = contactPromises.stream()
                .map(Promise::get)
                .toList();

        log.info("All {} contacts resolved for event: {}", resolvedContacts.size(), eventId);

        // --- Phase 2: Bundle by unique endpoint, create messages (MESSAGE_ACTIVITY_QUEUE) ---
        // Group contacts by unique phone/email to avoid duplicate messages.
        // In production, this would also consider channel preferences (voice/SMS/email).
        Map<String, List<ContactResult>> byEmail = resolvedContacts.stream()
                .filter(c -> c.email() != null && !c.email().isBlank())
                .collect(Collectors.groupingBy(ContactResult::email));

        // TODO: Production would also group by phone for SMS/voice channels,
        //       check contact preferences, and potentially create multiple messages
        //       per contact (e.g., SMS + email). For the POC, we create one message
        //       per unique email endpoint.
        for (var entry : byEmail.entrySet()) {
            // Use the first contact in the group as the primary recipient
            ContactResult primary = entry.getValue().get(0);
            messageActivities.createMessage(primary.id(), payload.templateId(), eventType);
        }

        // Also handle contacts with no email (phone-only in production)
        resolvedContacts.stream()
                .filter(c -> c.email() == null || c.email().isBlank())
                .forEach(contact -> {
                    // TODO: In production, route to SMS/voice based on phone + preferences
                    messageActivities.createMessage(contact.id(), payload.templateId(), eventType);
                });

        long messageCount = byEmail.size() + resolvedContacts.stream()
                .filter(c -> c.email() == null || c.email().isBlank()).count();
        log.info("Notification workflow completed for event: {} ({} contacts, {} messages)",
                eventId, resolvedContacts.size(), messageCount);
    }

    private ContactResult resolveContact(ContactInfo contact) {
        // ... existing logic unchanged ...
    }
}
```

**Key points:**
- The workflow itself still runs on `NOTIFICATION_QUEUE`. Only the `ActivityOptions.setTaskQueue()`
  on each stub changes where the activity tasks are placed.
- Phase 1 (contact resolution) blocks until ALL contacts are resolved — this is correct because
  Phase 2 needs the full set for bundling.
- Phase 2 groups contacts by shared email to create one message per unique endpoint.
- TODO comments mark where production logic (phone grouping, preferences, multi-channel) would go.

### 3. Update TemporalWorkerConfig — Profile-Based Worker Registration

**Do NOT modify `TemporalClientConfig`.** Only update its `@Profile` annotation to include
the new profiles (`contact-wf-worker`, `message-wf-worker`), or better, use a broader
profile expression. The client beans (WorkflowServiceStubs, WorkflowClient) are needed by
all worker profiles.

Update `TemporalClientConfig`'s profile to:
```java
@Profile({"ev-worker", "wf-worker", "contact-wf-worker", "message-wf-worker"})
```

Replace `TemporalWorkerConfig` with profile-based worker registration:

```java
@Configuration
public class TemporalWorkerConfig {

    /**
     * Combined mode: single worker handles workflow + all activities on their respective queues.
     * Active when profile is "wf-worker" WITHOUT the split sub-profiles active.
     * This is the local dev mode (bootRun with all profiles).
     */
    @Bean(initMethod = "start", destroyMethod = "shutdownNow")
    @Profile("wf-worker & !contact-wf-worker & !message-wf-worker")
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
     */
    @Bean(initMethod = "start", destroyMethod = "shutdownNow")
    @Profile("wf-worker & (contact-wf-worker | message-wf-worker)")
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
```

**Profile logic:**
- `bootRun` with `wf-worker` only (no `contact-wf-worker` or `message-wf-worker`) →
  `combinedWorkerFactory` activates. One JVM, 3 workers.
- K8s: `notification-wf-worker` pod runs `wf-worker,contact-wf-worker,message-wf-worker` but
  wait — that would activate the combined factory AND the split factories. Instead:
  - `notification-wf-worker` runs with profile `wf-worker` only → BUT we need it to be
    orchestrator-only in K8s. **Solution:** The wf-worker Helm deployment should activate BOTH
    `wf-worker` AND one of the split profiles (e.g., `wf-worker,contact-wf-worker`) so the
    `!contact-wf-worker` condition excludes the combined bean. Actually, a cleaner approach:

**Revised approach — use a dedicated `wf-worker-orchestrator` profile for the K8s wf-worker:**

- `notification-wf-worker` Helm deployment: `SPRING_PROFILES_ACTIVE=wf-worker-orchestrator`
- `notification-contact-wf-worker`: `SPRING_PROFILES_ACTIVE=contact-wf-worker`
- `notification-message-wf-worker`: `SPRING_PROFILES_ACTIVE=message-wf-worker`
- Local `bootRun`: `--spring.profiles.active=service,ev-worker,wf-worker`

Update `TemporalClientConfig`:
```java
@Profile({"ev-worker", "wf-worker", "wf-worker-orchestrator", "contact-wf-worker", "message-wf-worker"})
```

Update `TemporalWorkerConfig`:
```java
// Combined mode — local dev
@Profile("wf-worker")
public WorkerFactory combinedWorkerFactory(...) { /* registers all 3 workers */ }

// Orchestrator only — K8s wf-worker deployment
@Profile("wf-worker-orchestrator")
public WorkerFactory workflowOnlyFactory(...) { /* NOTIFICATION_QUEUE only */ }

// Contact activities — K8s contact-wf-worker deployment
@Profile("contact-wf-worker")
public WorkerFactory contactWorkerFactory(...) { /* CONTACT_ACTIVITY_QUEUE only */ }

// Message activities — K8s message-wf-worker deployment
@Profile("message-wf-worker")
public WorkerFactory messageWorkerFactory(...) { /* MESSAGE_ACTIVITY_QUEUE only */ }
```

This is cleaner — no compound profile expressions. Each profile maps to exactly one bean.

### 4. Profile-Specific YAML Files

Create `application-wf-worker-orchestrator.yml`:
```yaml
# Profile: wf-worker-orchestrator
# Workflow orchestration only (no activities). K8s deployment: notification-wf-worker.
# Web server runs only for actuator health/metrics.

spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
      - org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
      - org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
      - org.springframework.boot.data.jpa.autoconfigure.JpaRepositoriesAutoConfiguration
  kafka:
    listener:
      auto-startup: false

temporal:
  connection:
    target: ${TEMPORAL_ADDRESS:localhost:7233}
```

Create `application-contact-wf-worker.yml`:
```yaml
# Profile: contact-wf-worker
# Contact resolution activities only. K8s deployment: notification-contact-wf-worker.

spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
      - org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
      - org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
      - org.springframework.boot.data.jpa.autoconfigure.JpaRepositoriesAutoConfiguration
  kafka:
    listener:
      auto-startup: false

temporal:
  connection:
    target: ${TEMPORAL_ADDRESS:localhost:7233}

services:
  base-url: ${SERVICES_BASE_URL:http://notification-service:8080}
```

Create `application-message-wf-worker.yml`:
```yaml
# Profile: message-wf-worker
# Message creation activities only. K8s deployment: notification-message-wf-worker.

spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
      - org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
      - org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
      - org.springframework.boot.data.jpa.autoconfigure.JpaRepositoriesAutoConfiguration
  kafka:
    listener:
      auto-startup: false

temporal:
  connection:
    target: ${TEMPORAL_ADDRESS:localhost:7233}

services:
  base-url: ${SERVICES_BASE_URL:http://notification-service:8080}
```

Note: the `wf-worker-orchestrator` profile does NOT need `services.base-url` because the
orchestrator doesn't make HTTP calls — it only schedules activities. The activity workers
need it because they call the REST API.

### 5. Helm Templates for Split Workers

#### Update existing wf-worker deployment

Change the profile from `wf-worker` to `wf-worker-orchestrator`:

In `templates/wf-worker/deployment.yaml`, change:
```yaml
            - name: SPRING_PROFILES_ACTIVE
              value: "wf-worker-orchestrator"
```

Update the wf-worker ScaledObject to only watch workflow queue types:
```yaml
        queueTypes: "workflow"   # was "workflow,activity"
```

#### New: `templates/contact-wf-worker/`

Create `templates/contact-wf-worker/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: notification-contact-wf-worker
  labels: ...
spec:
  replicas: {{ .Values.contactWfWorker.replicas | default 1 }}
  ...
  containers:
    - name: notification-contact-wf-worker
      image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
      env:
        - name: SPRING_PROFILES_ACTIVE
          value: "contact-wf-worker"
      envFrom:
        - configMapRef:
            name: notification-contact-wf-worker-config
      # ... probes, resources from .Values.contactWfWorker ...
```

Create `templates/contact-wf-worker/configmap.yaml`:
```yaml
data:
  TEMPORAL_ADDRESS: {{ .Values.temporal.address | quote }}
  SERVICES_BASE_URL: {{ include "notification-poc.serviceUrl" . | quote }}
```

Create `templates/contact-wf-worker/scaled-object.yaml`:
```yaml
# Uses baseTriggers helper for cpu/memory, then typed temporal trigger
# referencing .Values.temporal.address, .Values.temporal.namespace,
# and .Values.temporal.contactActivityQueue for taskQueue.
# queueTypes: "activity"
```

#### New: `templates/message-wf-worker/`

Same pattern as contact-wf-worker but with:
- `SPRING_PROFILES_ACTIVE: "message-wf-worker"`
- ConfigMap name: `notification-message-wf-worker-config`
- ScaledObject targets `.Values.temporal.messageActivityQueue`
- `queueTypes: "activity"`

### 6. Helm Values Additions

Add new queue names and worker sections to `values.yaml`:

```yaml
temporal:
  address: "temporal-frontend.temporal:7233"
  taskQueue: "NOTIFICATION_QUEUE"
  contactActivityQueue: "CONTACT_ACTIVITY_QUEUE"
  messageActivityQueue: "MESSAGE_ACTIVITY_QUEUE"
  namespace: "default"

# --- Contact Activity Worker ---
contactWfWorker:
  replicas: 1
  resources:
    requests:
      cpu: "250m"
      memory: "384Mi"
    limits:
      cpu: "500m"
      memory: "768Mi"
  probes:
    liveness:
      initialDelaySeconds: 20
    readiness:
      initialDelaySeconds: 10
  scaling:
    enabled: true
    minReplicas: 1
    maxReplicas: 6
    cooldownPeriod: 60
    scaleDownStabilization: 120
    cpu:
      enabled: true
      targetUtilization: "60"
    memory:
      enabled: true
      targetUtilization: "75"
    temporal:
      enabled: true
      targetQueueSize: "3"
      queueTypes: "activity"
      unsafeSsl: "true"

# --- Message Activity Worker ---
messageWfWorker:
  replicas: 1
  resources:
    requests:
      cpu: "250m"
      memory: "384Mi"
    limits:
      cpu: "500m"
      memory: "768Mi"
  probes:
    liveness:
      initialDelaySeconds: 20
    readiness:
      initialDelaySeconds: 10
  scaling:
    enabled: true
    minReplicas: 1
    maxReplicas: 4
    cooldownPeriod: 60
    scaleDownStabilization: 120
    cpu:
      enabled: true
      targetUtilization: "60"
    memory:
      enabled: true
      targetUtilization: "75"
    temporal:
      enabled: true
      targetQueueSize: "5"
      queueTypes: "activity"
      unsafeSsl: "true"
```

Also update the existing `wfWorker` section:
```yaml
wfWorker:
  scaling:
    temporal:
      queueTypes: "workflow"   # changed from "workflow,activity"
```

Add KIND overrides to `environments/local-values.yaml`:
```yaml
contactWfWorker:
  resources:
    requests:
      cpu: "100m"
      memory: "256Mi"
    limits:
      cpu: "250m"
      memory: "512Mi"
  scaling:
    maxReplicas: 3
    temporal:
      targetQueueSize: "2"

messageWfWorker:
  resources:
    requests:
      cpu: "100m"
      memory: "256Mi"
    limits:
      cpu: "250m"
      memory: "512Mi"
  scaling:
    maxReplicas: 3
    temporal:
      targetQueueSize: "3"
```

### 7. Update `.cursor/rules/20-temporal-workflows.mdc`

Add a section documenting the task queue splitting pattern, the per-queue activity stubs,
the profile-to-deployment mapping, and the combined vs split mode.

## Acceptance Criteria

- [ ] `TaskQueues` constants class exists in `notification-common`.
- [ ] `NotificationWorkflowImpl` routes contact activities to `CONTACT_ACTIVITY_QUEUE` and
  message activities to `MESSAGE_ACTIVITY_QUEUE` via `ActivityOptions.setTaskQueue()`.
- [ ] Basic message bundling: contacts sharing the same email get one message (grouped).
- [ ] `TemporalWorkerConfig` has 4 profile-gated factory beans: combined (`wf-worker`),
  orchestrator (`wf-worker-orchestrator`), contact (`contact-wf-worker`),
  message (`message-wf-worker`).
- [ ] `TemporalClientConfig` profile includes all worker profiles.
- [ ] Profile YAMLs exist for `wf-worker-orchestrator`, `contact-wf-worker`, `message-wf-worker`.
- [ ] Helm chart deploys 5 total deployments: service, ev-worker, wf-worker (orchestrator),
  contact-wf-worker, message-wf-worker.
- [ ] KEDA creates 5 HPAs: `kubectl get hpa` shows HPAs for all deployments.
- [ ] Each wf-worker ScaledObject targets its specific queue and queue type.
- [ ] Full e2e flow works: POST /events → workflow runs → contacts resolved on
  `CONTACT_ACTIVITY_QUEUE` → messages created on `MESSAGE_ACTIVITY_QUEUE`.
- [ ] Local dev with `--spring.profiles.active=service,ev-worker,wf-worker` still works
  (combined mode, backward compatible).
- [ ] `.cursor/rules/20-temporal-workflows.mdc` updated with task queue splitting docs.

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/14-temporal-queue-split.md (this task — full design and code samples)
- notification-app/src/main/java/.../workflow/NotificationWorkflowImpl.java (current workflow)
- notification-app/src/main/java/.../workflow/NotificationWorkflow.java (interface)
- notification-app/src/main/java/.../activity/ContactActivities.java (interface)
- notification-app/src/main/java/.../activity/MessageActivities.java (interface)
- notification-app/src/main/java/.../activity/ContactActivitiesImpl.java (implementation)
- notification-app/src/main/java/.../activity/MessageActivitiesImpl.java (implementation)
- notification-app/src/main/java/.../worker/TemporalWorkerConfig.java (current worker config)
- notification-app/src/main/java/.../worker/TemporalClientConfig.java (Temporal client beans)
- notification-app/src/main/resources/application-wf-worker.yml (current worker config)
- notification-common/src/main/java/.../model/dto/ContactResult.java (has email, phone fields)
- charts/notification-poc/values.yaml (Helm values)
- charts/notification-poc/templates/wf-worker/ (existing wf-worker templates — use as pattern)
- charts/notification-poc/templates/_helpers.tpl (Helm helpers: baseTriggers, serviceUrl, labels)
- .cursor/rules/20-temporal-workflows.mdc (Temporal patterns)

Task: Split Temporal activities onto separate task queues and add basic message bundling.

Steps:
1. Create TaskQueues constants class in notification-common.
2. Update NotificationWorkflowImpl: add setTaskQueue() to all activity stubs,
   add basic email-based message bundling in Phase 2.
3. Replace TemporalWorkerConfig with profile-based worker registration
   (combined, orchestrator-only, contact-only, message-only).
4. Update TemporalClientConfig @Profile to include new worker profiles.
5. Create application-wf-worker-orchestrator.yml, application-contact-wf-worker.yml,
   and application-message-wf-worker.yml profile configs.
6. Update existing wf-worker Helm deployment to use wf-worker-orchestrator profile.
7. Update existing wf-worker ScaledObject queueTypes from "workflow,activity" to "workflow".
8. Add temporal.contactActivityQueue and temporal.messageActivityQueue to values.yaml.
9. Create Helm templates for contact-wf-worker/ (deployment, configmap, scaled-object).
10. Create Helm templates for message-wf-worker/ (deployment, configmap, scaled-object).
11. Add contactWfWorker and messageWfWorker sections to values.yaml and local-values.yaml.
12. Run helm lint and helm template to validate.
13. Build Docker image, load into KIND, install chart.
14. Verify 5 deployments running, 5 HPAs created.
15. Port-forward and test full e2e flow.
16. Test local bootRun with wf-worker profile (combined mode) still works.
17. Update .cursor/rules/20-temporal-workflows.mdc.

Key rules:
- Workflow code runs on NOTIFICATION_QUEUE. Activity tasks are placed on their specific queues
  via ActivityOptions.setTaskQueue(). The workflow itself does NOT change queues.
- TemporalClientConfig stays separate from TemporalWorkerConfig. Only update its @Profile.
- Use setUsingVirtualThreadsOnActivityWorker(true) for all activity workers.
- KEDA ScaledObjects use typed trigger pattern — addresses from top-level values, not hardcoded.
- ConfigMaps use the notification-poc.serviceUrl helper for SERVICES_BASE_URL.
- New Helm templates follow the exact same pattern as existing wf-worker/ templates.
- The Helm chart ALWAYS deploys all 3 wf-worker types. No feature flag.
- Combined mode (local bootRun with wf-worker profile) must still work.
```
