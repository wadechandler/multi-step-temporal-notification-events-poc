{{/*
Expand the name of the chart.
*/}}
{{- define "notification-poc.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "notification-poc.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels for a component.
Usage: {{ include "notification-poc.labels" (dict "name" "notification-service" "context" $) }}
*/}}
{{- define "notification-poc.labels" -}}
app: {{ .name }}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .context.Release.Name }}
app.kubernetes.io/managed-by: {{ .context.Release.Service }}
helm.sh/chart: {{ include "notification-poc.chart" .context }}
{{- end }}

{{/*
Selector labels for a component.
Usage: {{ include "notification-poc.selectorLabels" (dict "name" "notification-service") }}
*/}}
{{- define "notification-poc.selectorLabels" -}}
app: {{ .name }}
{{- end }}

{{/*
Canonical name for the notification-service K8s Service.
Used by the Service resource, ConfigMaps (SERVICES_BASE_URL), and the PromQL query.
Single source of truth — change here to rename the service everywhere.
*/}}
{{- define "notification-poc.serviceName" -}}
notification-service
{{- end }}

{{/*
Internal cluster URL for the notification-service.
Used by ConfigMaps to set SERVICES_BASE_URL for wf-worker activity HTTP calls
and for the service profile's self-reference.
*/}}
{{- define "notification-poc.serviceUrl" -}}
http://{{ include "notification-poc.serviceName" . }}:8080
{{- end }}

{{/*
Baseline cpu + memory KEDA triggers (safety-net scaling).
Each component's ScaledObject includes these, then adds its own
app-specific trigger that references infrastructure addresses from
top-level values (kafka.bootstrapServers, temporal.address, prometheus.serverAddress).

Usage (inside a triggers: list):
  {{- include "notification-poc.baseTriggers" .Values.service.scaling | nindent 4 }}
*/}}
{{- define "notification-poc.baseTriggers" -}}
{{- if .cpu.enabled }}
- type: cpu
  metricType: Utilization
  metadata:
    value: {{ .cpu.targetUtilization | quote }}
{{- end }}
{{- if .memory.enabled }}
- type: memory
  metricType: Utilization
  metadata:
    value: {{ .memory.targetUtilization | quote }}
{{- end }}
{{- end -}}
