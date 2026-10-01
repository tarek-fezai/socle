{{- define "socle.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "socle.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "socle.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end }}

{{- define "socle.labels" -}}
helm.sh/chart: {{ include "socle.chart" . }}
app.kubernetes.io/name: {{ include "socle.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "socle.selectorLabels" -}}
app.kubernetes.io/name: {{ include "socle.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "socle.backend.selectorLabels" -}}
{{ include "socle.selectorLabels" . }}
app.kubernetes.io/component: backend
{{- end }}

{{- define "socle.frontend.selectorLabels" -}}
{{ include "socle.selectorLabels" . }}
app.kubernetes.io/component: frontend
{{- end }}

{{- define "socle.webhookWorker.selectorLabels" -}}
{{ include "socle.selectorLabels" . }}
app.kubernetes.io/component: webhook-worker
{{- end }}

{{- define "socle.image" -}}
{{- $registry := .Values.global.imageRegistry -}}
{{- $repo := .repository -}}
{{- $tag := default .Chart.AppVersion .tag -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" $registry $repo $tag }}
{{- else -}}
{{- printf "%s:%s" $repo $tag }}
{{- end -}}
{{- end }}

{{- define "socle.postgres.host" -}}
{{- if .Values.postgres.embedded -}}
{{- printf "%s-postgres" (include "socle.fullname" .) -}}
{{- else -}}
{{- required "postgres.host is required when postgres.embedded=false" .Values.postgres.host -}}
{{- end -}}
{{- end }}

{{- define "socle.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "socle.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end }}

{{- define "socle.secretName" -}}
{{- if .Values.backend.existingSecret -}}
{{- .Values.backend.existingSecret -}}
{{- else -}}
{{- include "socle.fullname" . -}}-secrets
{{- end -}}
{{- end }}
