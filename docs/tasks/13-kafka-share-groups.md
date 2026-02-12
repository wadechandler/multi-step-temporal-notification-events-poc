# Task 13: Kafka Share Groups Feature Flag

## Context

Apache Kafka 4.1 introduces **share groups** (KIP-932) as a preview feature. Share groups
provide queue-like consumption semantics: the broker distributes individual records across
consumers (record-level delivery with per-message acks), unlike traditional consumer groups
where each partition is assigned to exactly one consumer.

This is valuable for workloads like our notification event processing where:
- Events are independent (no ordering requirement between events)
- Dynamic scaling is desired (add/remove consumers without rebalancing)
- Broker-managed retries simplify consumer error handling

However, not all Kafka clusters can be upgraded at once. Some workloads DO require partition
ordering (e.g., commands for a specific entity). The application needs to support **both
modes** via configuration, so the same codebase works with older clusters (consumer groups)
and newer clusters (share groups).

### KIP-932 Upgrade Implications

These are critical for your team to understand:

1. **4.0 early access is INCOMPATIBLE with 4.1 preview.** If share groups were enabled on a
   Kafka 4.0 cluster, that cluster cannot be upgraded to 4.1. The share consumer from 4.0
   does not work with a 4.1 cluster and vice versa.
2. **All brokers must be 4.1+** before enabling share groups. Rolling upgrades must complete
   fully first.
3. **Cluster-level feature toggle**: `kafka-features.sh upgrade --feature share.version=1`.
   Reversible: `kafka-features.sh downgrade --feature share.version=0`.
4. **Preview only** — not recommended for production clusters yet.

### Spring Kafka 4.x Support

Spring Kafka (which ships with Spring Boot 4.0.2) has built-in share group support:
- `ShareConsumerFactory` / `DefaultShareConsumerFactory` — analogous to `ConsumerFactory`
- Share message listener containers
- Same configuration pattern (bootstrap servers, deserializers, group ID)

This task is **parallelizable with Task 14** (Temporal task queue splitting) since they
touch independent code paths (Kafka consumers vs Temporal workers).

## Prerequisites
- Task 10 complete (multi-module structure with `notification-messaging/`)
- Task 11 complete (Kafka 4.1.1 with share groups enabled)
- Task 12 complete (Helm deployment working)

### Important: Changes Since This Task Was Written
- **Consumer group ID is now injectable.** `application-ev-worker.yml` uses
  `group-id: ${KAFKA_CONSUMER_GROUP_ID:notification-workflow-starter}` (not a hardcoded string).
  The ConfigMap provides `KAFKA_CONSUMER_GROUP_ID` from `values.evWorker.kafka.consumerGroup`.
  When creating the share group consumer, use `${KAFKA_SHARE_GROUP_ID:notification-share-group}`
  for the share group ID and wire it through the same ConfigMap/values path.
- **Spring Boot 4 autoconfig packages** were reorganized. Use
  `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` (not
  `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`), etc.
  See the existing `application-ev-worker.yml` for the correct class names.

## Key Files to Read
- `notification-messaging/src/main/java/` — Kafka infrastructure module
- `notification-app/src/main/java/.../consumer/NotificationEventConsumer.java` — Current consumer
- `notification-app/src/main/resources/application-ev-worker.yml` — ev-worker config
- `charts/notification-poc/values.yaml` — Helm values (evWorker section)
- `charts/notification-poc/templates/ev-worker/configmap.yaml` — ev-worker ConfigMap

## Deliverables

### 1. Dual-Mode Consumer Configuration in `notification-messaging`

Add a property-driven consumer factory configuration:

```java
package com.wadechandler.notification.poc.messaging;

@Configuration
@EnableKafka
public class KafkaConsumerModeConfig {

    /**
     * Standard consumer group factory — used when kafka.consumer-mode=consumer-group
     * or when the property is not set (default).
     */
    @Bean
    @ConditionalOnProperty(
        name = "kafka.consumer-mode",
        havingValue = "consumer-group",
        matchIfMissing = true
    )
    public ConsumerFactory<String, String> consumerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaConsumerFactory<>(kafkaProperties.buildConsumerProperties());
    }

    /**
     * Share group consumer factory — used when kafka.consumer-mode=share-group.
     * Requires Kafka 4.1+ cluster with share.version=1 enabled.
     */
    @Bean
    @ConditionalOnProperty(
        name = "kafka.consumer-mode",
        havingValue = "share-group"
    )
    public ShareConsumerFactory<String, String> shareConsumerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties();
        return new DefaultShareConsumerFactory<>(config);
    }
}
```

### 2. Update NotificationEventConsumer for Both Modes

The consumer needs to work with both modes. Two approaches:

**Approach A: Two consumer beans, conditionally activated**

```java
@Component
@Profile("ev-worker")
@ConditionalOnProperty(name = "kafka.consumer-mode", havingValue = "consumer-group", matchIfMissing = true)
public class NotificationEventConsumer {
    // Existing @KafkaListener-based consumer
    @KafkaListener(topics = "notification-events", groupId = "notification-workflow-starter")
    public void consume(ConsumerRecord<String, String> record, ...) { ... }
}

@Component
@Profile("ev-worker")
@ConditionalOnProperty(name = "kafka.consumer-mode", havingValue = "share-group")
public class NotificationEventShareConsumer {
    // Share group consumer — uses @KafkaShareListener (Spring Kafka 4.x)
    // Records are distributed across instances by the broker, no partition assignment
    @KafkaShareListener(topics = "notification-events", groupId = "notification-share-group")
    public void consume(ConsumerRecord<String, String> record, ...) { ... }
}
```

**Approach B: Shared base class with common logic**

Extract the event processing logic into a shared method, and have two thin consumer classes
that delegate to it. This avoids duplicating the workflow-starting logic.

```java
// Shared event processing logic
@Component
@Profile("ev-worker")
public class NotificationEventProcessor {
    // Contains all the event parsing + workflow starting logic
    public void processEvent(ConsumerRecord<String, String> record) { ... }
}

// Consumer group mode
@Component
@Profile("ev-worker")
@ConditionalOnProperty(name = "kafka.consumer-mode", havingValue = "consumer-group", matchIfMissing = true)
public class NotificationEventConsumer {
    @KafkaListener(topics = "notification-events", groupId = "notification-workflow-starter")
    public void consume(ConsumerRecord<String, String> record) {
        processor.processEvent(record);
    }
}

// Share group mode
@Component
@Profile("ev-worker")
@ConditionalOnProperty(name = "kafka.consumer-mode", havingValue = "share-group")
public class NotificationEventShareConsumer {
    @KafkaShareListener(topics = "notification-events", groupId = "notification-share-group")
    public void consume(ConsumerRecord<String, String> record) {
        processor.processEvent(record);
    }
}
```

**Recommendation:** Approach B (shared base). Less code duplication, easier to maintain.

### 3. Application Configuration

Add the consumer mode property to the ev-worker profile config:

```yaml
# application-ev-worker.yml
kafka:
  consumer-mode: ${KAFKA_CONSUMER_MODE:consumer-group}
```

### 4. Helm Values Integration

The consumer mode is already wired in Task 12's ConfigMap:

```yaml
# In values.yaml (evWorker section)
evWorker:
  kafka:
    consumerMode: "consumer-group"    # or "share-group"
```

Verify that `KAFKA_CONSUMER_MODE` flows from values -> ConfigMap -> env var -> Spring property.

### 5. Testing

Test both modes in KIND:

```bash
# Test consumer group mode (default)
helm upgrade notification-poc charts/notification-poc \
    -f charts/notification-poc/environments/local-values.yaml \
    --set evWorker.kafka.consumerMode=consumer-group

# Submit test events and verify they are processed
curl -X POST http://localhost:8080/events ...

# Switch to share group mode
helm upgrade notification-poc charts/notification-poc \
    -f charts/notification-poc/environments/local-values.yaml \
    --set evWorker.kafka.consumerMode=share-group

# Wait for pods to restart
kubectl rollout status deployment/notification-ev-worker

# Submit test events and verify they are processed in share group mode
curl -X POST http://localhost:8080/events ...

# Verify share group is visible
kubectl exec -n kafka poc-kafka-combined-0 -- \
    /opt/kafka/bin/kafka-share-groups.sh \
    --bootstrap-server localhost:9092 --list
```

### 6. Documentation

Create `docs/guides/kafka-share-groups.md` covering:
- What share groups are and when to use them vs consumer groups
- The feature flag (`kafka.consumer-mode`) and how to toggle it
- KIP-932 upgrade implications (the 4.0/4.1 incompatibility, all-brokers requirement)
- How to verify share groups are working (kafka-share-groups.sh commands)
- When to use consumer groups (ordering needed, older clusters)
- When to use share groups (independent events, dynamic scaling, no ordering needed)

## Acceptance Criteria

- [ ] `kafka.consumer-mode=consumer-group` works (default, existing behavior preserved)
- [ ] `kafka.consumer-mode=share-group` works (events processed via share consumer)
- [ ] Switching modes via Helm values triggers a pod restart and the new mode activates
- [ ] Share group is visible via `kafka-share-groups.sh --list`
- [ ] Full e2e flow works in both modes (events -> workflows -> contacts + messages)
- [ ] `docs/guides/kafka-share-groups.md` documents both modes and upgrade implications
- [ ] No code duplication: event processing logic is shared between both consumer implementations

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/13-kafka-share-groups.md (this task — full feature flag approach)
- notification-messaging/src/main/java/ (Kafka infrastructure module)
- notification-app/src/main/java/.../consumer/NotificationEventConsumer.java (current consumer)
- notification-app/src/main/resources/application-ev-worker.yml (ev-worker config)
- charts/notification-poc/values.yaml (Helm values, evWorker section)
- charts/notification-poc/templates/ev-worker/configmap.yaml (ConfigMap)

Task: Implement dual-mode Kafka consumer (consumer-group vs share-group) with a feature flag.

Steps:
1. In notification-messaging, create KafkaConsumerModeConfig with @ConditionalOnProperty
   beans for both ConsumerFactory and ShareConsumerFactory.
2. Extract the event processing logic from NotificationEventConsumer into a shared
   NotificationEventProcessor class.
3. Create NotificationEventConsumer (consumer-group mode, @KafkaListener) and
   NotificationEventShareConsumer (share-group mode, @KafkaShareListener), both
   delegating to the shared processor.
4. Add kafka.consumer-mode property to application-ev-worker.yml.
5. Verify Helm values -> ConfigMap -> env var -> Spring property flow works.
6. Test consumer-group mode: deploy, submit events, verify processing.
7. Switch to share-group mode via helm upgrade --set, verify processing.
8. Verify share group is visible via kafka-share-groups.sh.
9. Create docs/guides/kafka-share-groups.md with documentation.

Key rules:
- Default mode is consumer-group (matchIfMissing = true). Existing behavior must be preserved.
- Share group mode requires Kafka 4.1+ with share.version=1 enabled (already done in Task 11).
- Use Spring Kafka 4.x ShareConsumerFactory / DefaultShareConsumerFactory.
- No code duplication between the two consumer modes — shared processor class.
- Document the KIP-932 upgrade implications clearly for the team.
```
