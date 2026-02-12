# Task 12: Helm Charts + KIND Deployment

## Context

After Task 10 (restructure) and Task 11 (infra updates), the application is a multi-module
project and the KIND cluster has KEDA installed. But the application still runs locally via
`bootRun` -- it's not deployed into Kubernetes.

This task creates a Helm chart that deploys the application as **three separate workloads**
inside KIND, all from the same Docker image but with different Spring profiles:

- **notification-service**: REST APIs + CQRS command handlers (`profile=service`)
- **notification-ev-worker**: Kafka event consumer (`profile=ev-worker`)
- **notification-wf-worker**: Temporal workflow + activities worker (`profile=wf-worker`)

Each workload gets its own KEDA `ScaledObject` with a composable trigger pattern:
- **Baseline**: cpu + memory utilization (safety net)
- **Application-specific**: prometheus p90 latency, kafka consumer lag, or temporal queue backlog

All scaling configuration is injectable via Helm values, consistent across all components.

## Prerequisites
- Task 10 complete (multi-module structure with Spring profiles)
- Task 11 complete (KEDA operator installed, Kafka 4.1.1 running)

## Key Files to Read
- `AGENTS.md` — Architecture overview and new phases
- `notification-app/Dockerfile` — Current Docker build
- `infra/scripts/setup.sh` — Infrastructure setup (to add app deployment step)
- `app/src/main/resources/application.yml` — Current config structure
- The profile-specific YAML files created in Task 10

## Deliverables

### 1. Helm Chart Structure

Create `charts/notification-poc/` with this layout:

```
charts/notification-poc/
  Chart.yaml
  values.yaml
  environments/
    local-values.yaml               (KIND-specific overrides)
  templates/
    _helpers.tpl                    (shared template helpers)
    service/
      deployment.yaml
      service.yaml
      configmap.yaml
      scaled-object.yaml
    ev-worker/
      deployment.yaml
      configmap.yaml
      scaled-object.yaml
    wf-worker/
      deployment.yaml
      configmap.yaml
      scaled-object.yaml
```

### 2. `Chart.yaml`

```yaml
apiVersion: v2
name: notification-poc
description: Multi-step Temporal notification engine POC
type: application
version: 0.1.0
appVersion: "0.1.0-SNAPSHOT"
```

### 3. Reusable KEDA ScaledObject Template (`_helpers.tpl`)

Define a named template that any component can invoke to create its ScaledObject.
Every component gets cpu + memory baseline triggers; each adds its own custom triggers.

```yaml
{{- define "notification-poc.scaledObject" -}}
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: {{ .name }}
  labels:
    app: {{ .name }}
spec:
  scaleTargetRef:
    name: {{ .name }}
  minReplicaCount: {{ .scaling.minReplicas | default 1 }}
  maxReplicaCount: {{ .scaling.maxReplicas | default 5 }}
  cooldownPeriod: {{ .scaling.cooldownPeriod | default 90 }}
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleDown:
          stabilizationWindowSeconds: {{ .scaling.scaleDownStabilization | default 180 }}
  triggers:
    {{- if .scaling.cpu.enabled }}
    - type: cpu
      metricType: Utilization
      metadata:
        value: {{ .scaling.cpu.targetUtilization | quote }}
    {{- end }}
    {{- if .scaling.memory.enabled }}
    - type: memory
      metricType: Utilization
      metadata:
        value: {{ .scaling.memory.targetUtilization | quote }}
    {{- end }}
    {{- range .scaling.customTriggers }}
    - type: {{ .type }}
      metadata:
        {{- toYaml .metadata | nindent 8 }}
    {{- end }}
{{- end -}}
```

Also define helpers for common labels, selectors, image references, etc.

### 4. Deployment Templates

Each deployment template follows the same pattern. Example for the service:

```yaml
# templates/service/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: notification-service
  labels:
    app: notification-service
spec:
  replicas: {{ .Values.service.replicas | default 1 }}
  selector:
    matchLabels:
      app: notification-service
  template:
    metadata:
      labels:
        app: notification-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      containers:
        - name: notification-service
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "service"
            - name: BUSINESS_DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: business-db-app
                  key: password
          envFrom:
            - configMapRef:
                name: notification-service-config
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: {{ .Values.service.probes.liveness.initialDelaySeconds | default 30 }}
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: {{ .Values.service.probes.readiness.initialDelaySeconds | default 15 }}
            periodSeconds: 5
          resources:
            requests:
              cpu: {{ .Values.service.resources.requests.cpu | default "500m" }}
              memory: {{ .Values.service.resources.requests.memory | default "512Mi" }}
            limits:
              cpu: {{ .Values.service.resources.limits.cpu | default "1000m" }}
              memory: {{ .Values.service.resources.limits.memory | default "1Gi" }}
```

The `ev-worker` and `wf-worker` deployment templates are similar but with:
- Different `SPRING_PROFILES_ACTIVE` values
- Different ConfigMap references
- Different resource limits (workers typically need less CPU, more memory)
- The ev-worker does NOT need the DB password secret
- The wf-worker does NOT need the DB password secret

### 5. ConfigMap Templates

Each deployment gets a ConfigMap for its environment-specific config:

```yaml
# templates/service/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: notification-service-config
data:
  KAFKA_BOOTSTRAP_SERVERS: {{ .Values.kafka.bootstrapServers | quote }}
  DB_HOST: {{ .Values.database.host | quote }}
  DB_PORT: {{ .Values.database.port | quote }}
  DB_USER: {{ .Values.database.user | quote }}
  SERVICES_BASE_URL: "http://notification-service:8080"

# templates/ev-worker/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: notification-ev-worker-config
data:
  KAFKA_BOOTSTRAP_SERVERS: {{ .Values.kafka.bootstrapServers | quote }}
  TEMPORAL_ADDRESS: {{ .Values.temporal.address | quote }}
  KAFKA_CONSUMER_MODE: {{ .Values.evWorker.kafka.consumerMode | default "consumer-group" | quote }}

# templates/wf-worker/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: notification-wf-worker-config
data:
  TEMPORAL_ADDRESS: {{ .Values.temporal.address | quote }}
  TEMPORAL_TASK_QUEUE: {{ .Values.temporal.taskQueue | default "NOTIFICATION_QUEUE" | quote }}
  SERVICES_BASE_URL: "http://notification-service:8080"
```

### 6. ScaledObject Templates

Each deployment gets its ScaledObject using the shared template:

```yaml
# templates/service/scaled-object.yaml
{{- if .Values.service.scaling.enabled }}
{{- $ctx := dict "name" "notification-service" "scaling" .Values.service.scaling }}
{{ include "notification-poc.scaledObject" $ctx }}
{{- end }}
```

### 7. Kubernetes Service (for notification-service only)

```yaml
# templates/service/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: notification-service
spec:
  selector:
    app: notification-service
  ports:
    - port: 8080
      targetPort: 8080
  type: ClusterIP
```

The workers don't need a Service (they're consumers, not servers) unless you want
actuator metrics exposed -- in which case add a headless Service for Prometheus scraping.

### 8. `values.yaml` — Default Configuration

```yaml
image:
  repository: notification-poc
  tag: latest
  pullPolicy: IfNotPresent

kafka:
  bootstrapServers: "poc-kafka-kafka-bootstrap.kafka:9092"

database:
  host: "business-db-rw.default"
  port: "5432"
  user: "app"

temporal:
  address: "temporal-frontend.temporal:7233"
  taskQueue: "NOTIFICATION_QUEUE"

# --- Service ---
service:
  replicas: 1
  resources:
    requests:
      cpu: "500m"
      memory: "512Mi"
    limits:
      cpu: "1000m"
      memory: "1Gi"
  probes:
    liveness:
      initialDelaySeconds: 30
    readiness:
      initialDelaySeconds: 15
  scaling:
    enabled: true
    minReplicas: 1
    maxReplicas: 5
    cooldownPeriod: 90
    scaleDownStabilization: 180
    cpu:
      enabled: true
      targetUtilization: "70"
    memory:
      enabled: true
      targetUtilization: "80"
    customTriggers:
      - type: prometheus
        metadata:
          serverAddress: "http://kube-prometheus-stack-prometheus.monitoring:9090"
          query: |
            histogram_quantile(0.90,
              sum(rate(http_server_requests_seconds_bucket{
                namespace="default",
                app="notification-service"
              }[2m])) by (le))
          threshold: "0.03"

# --- Event Worker ---
evWorker:
  replicas: 1
  kafka:
    consumerMode: "consumer-group"
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
    maxReplicas: 5
    cooldownPeriod: 60
    scaleDownStabilization: 120
    cpu:
      enabled: true
      targetUtilization: "60"
    memory:
      enabled: true
      targetUtilization: "75"
    customTriggers:
      - type: kafka
        metadata:
          bootstrapServers: "poc-kafka-kafka-bootstrap.kafka:9092"
          consumerGroup: "notification-workflow-starter"
          topic: "notification-events"
          lagThreshold: "10"

# --- Workflow Worker ---
wfWorker:
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
    maxReplicas: 8
    cooldownPeriod: 60
    scaleDownStabilization: 120
    cpu:
      enabled: true
      targetUtilization: "60"
    memory:
      enabled: true
      targetUtilization: "75"
    customTriggers:
      - type: temporal
        metadata:
          endpoint: "temporal-frontend.temporal:7233"
          namespace: "default"
          taskQueue: "NOTIFICATION_QUEUE"
          targetQueueSize: "5"
          queueTypes: "workflow,activity"
          unsafeSsl: "true"
```

### 9. `environments/local-values.yaml` — KIND Overrides

Lower resource limits and thresholds for KIND:

```yaml
service:
  resources:
    requests:
      cpu: "100m"
      memory: "256Mi"
    limits:
      cpu: "500m"
      memory: "512Mi"
  scaling:
    maxReplicas: 3
    customTriggers:
      - type: prometheus
        metadata:
          serverAddress: "http://kube-prometheus-stack-prometheus.monitoring:9090"
          query: |
            histogram_quantile(0.90,
              sum(rate(http_server_requests_seconds_bucket{
                namespace="default",
                app="notification-service"
              }[2m])) by (le))
          threshold: "0.02"

evWorker:
  resources:
    requests:
      cpu: "100m"
      memory: "256Mi"
    limits:
      cpu: "250m"
      memory: "512Mi"
  scaling:
    maxReplicas: 3
    customTriggers:
      - type: kafka
        metadata:
          bootstrapServers: "poc-kafka-kafka-bootstrap.kafka:9092"
          consumerGroup: "notification-workflow-starter"
          topic: "notification-events"
          lagThreshold: "5"

wfWorker:
  resources:
    requests:
      cpu: "100m"
      memory: "256Mi"
    limits:
      cpu: "250m"
      memory: "512Mi"
  scaling:
    maxReplicas: 4
    customTriggers:
      - type: temporal
        metadata:
          endpoint: "temporal-frontend.temporal:7233"
          namespace: "default"
          taskQueue: "NOTIFICATION_QUEUE"
          targetQueueSize: "3"
          queueTypes: "workflow,activity"
          unsafeSsl: "true"
```

### 10. Update Dockerfile

The Dockerfile needs to build from the project root (not `app/`):

```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY settings.gradle .
COPY build.gradle .
COPY notification-common/build.gradle notification-common/build.gradle
COPY notification-repository/build.gradle notification-repository/build.gradle
COPY notification-messaging/build.gradle notification-messaging/build.gradle
COPY notification-app/build.gradle notification-app/build.gradle

# Download dependencies (cached layer)
RUN ./gradlew :notification-app:dependencies --no-daemon || true

# Copy all source
COPY notification-common/src notification-common/src
COPY notification-repository/src notification-repository/src
COPY notification-messaging/src notification-messaging/src
COPY notification-app/src notification-app/src

# Build
RUN ./gradlew :notification-app:bootJar --no-daemon

# Runtime image
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/notification-app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
```

Move the Dockerfile to the project root (or keep in `notification-app/` and set build
context to root).

### 11. Build + Load Script

Create `scripts/build-and-load.sh`:

```bash
#!/usr/bin/env bash
# Build the Docker image and load it into the KIND cluster
set -euo pipefail

IMAGE_NAME="${1:-notification-poc}"
IMAGE_TAG="${2:-latest}"

echo "Building Docker image ${IMAGE_NAME}:${IMAGE_TAG}..."
docker build -t "${IMAGE_NAME}:${IMAGE_TAG}" -f Dockerfile .

echo "Loading image into KIND cluster..."
kind load docker-image "${IMAGE_NAME}:${IMAGE_TAG}" --name notification-poc

echo "Image loaded. Deploy with:"
echo "  helm install notification-poc charts/notification-poc -f charts/notification-poc/environments/local-values.yaml"
```

### 12. Update `setup.sh` (Optional App Deployment)

Add an optional step at the end of `setup.sh` to deploy the application:

```bash
if [[ "${DEPLOY_APP:-false}" == "true" ]]; then
    echo "Building and deploying application..."
    ./scripts/build-and-load.sh

    helm upgrade --install notification-poc charts/notification-poc \
        -f charts/notification-poc/environments/local-values.yaml \
        --namespace default \
        --wait --timeout 300s

    echo "Application deployed."
fi
```

## Acceptance Criteria

- [ ] `charts/notification-poc/` directory exists with all templates
- [ ] `helm template notification-poc charts/notification-poc -f charts/notification-poc/environments/local-values.yaml` renders valid YAML
- [ ] `helm lint charts/notification-poc` passes
- [ ] Docker image builds: `docker build -t notification-poc:latest -f Dockerfile .`
- [ ] Image loads into KIND: `kind load docker-image notification-poc:latest --name notification-poc`
- [ ] `helm install` succeeds and all three deployments start:
  - `notification-service` pods are Running and Ready
  - `notification-ev-worker` pods are Running and Ready
  - `notification-wf-worker` pods are Running and Ready
- [ ] KEDA creates HPAs for each deployment: `kubectl get hpa` shows 3 HPAs
- [ ] Full e2e flow works through the Kubernetes-deployed services:
  - Port-forward notification-service and POST /events
  - Workflow appears in Temporal UI
  - Contacts and messages created in database
- [ ] `scripts/build-and-load.sh` works end-to-end

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/12-helm-kind-deploy.md (this task — full Helm chart structure, templates, values)
- AGENTS.md (architecture overview)
- notification-app/Dockerfile (current Docker build to update)
- infra/scripts/setup.sh (to add optional app deployment)
- notification-app/src/main/resources/application.yml (config structure)
- notification-app/src/main/resources/application-service.yml (profile config)
- notification-app/src/main/resources/application-ev-worker.yml (profile config)
- notification-app/src/main/resources/application-wf-worker.yml (profile config)

Task: Create the Helm chart and deploy the application into KIND.

Steps:
1. Create charts/notification-poc/ directory with Chart.yaml.
2. Create templates/_helpers.tpl with the reusable KEDA ScaledObject template and common helpers.
3. Create deployment, configmap, and scaled-object templates for service, ev-worker, wf-worker.
4. Create service.yaml for notification-service (ClusterIP).
5. Create values.yaml with the full per-component configuration.
6. Create environments/local-values.yaml with KIND-appropriate overrides.
7. Move/update Dockerfile to project root for multi-module build.
8. Create scripts/build-and-load.sh.
9. Run helm lint and helm template to validate.
10. Build the Docker image and load into KIND.
11. Install the Helm chart and verify all deployments come up.
12. Port-forward notification-service and test the e2e flow.
13. Verify KEDA created HPAs: kubectl get hpa

Key rules:
- All three deployments use the SAME Docker image with different SPRING_PROFILES_ACTIVE.
- KEDA ScaledObjects use the composable trigger pattern (cpu + memory + app-specific).
- Java-aware scaling: cooldownPeriod=90, scaleDown stabilization=180.
- Workers connect to services via internal cluster DNS (e.g., notification-service:8080).
- Database password comes from the existing CNPG K8s secret (business-db-app).
- Temporal frontend is at temporal-frontend.temporal:7233 (internal).
- Kafka bootstrap is at poc-kafka-kafka-bootstrap.kafka:9092 (internal).
- All thresholds should be tuned for KIND (low resource limits, low trigger thresholds).
```
