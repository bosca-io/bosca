{{/*
Chart name, truncated to 63 characters.
*/}}
{{- define "bosca-common.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name. Uses fullnameOverride if set, otherwise
release-name + chart-name, truncated to 63 characters.
*/}}
{{- define "bosca-common.fullname" -}}
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

{{/*
Chart label value: <name>-<version>.
*/}}
{{- define "bosca-common.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Service account name. Uses the fullname if serviceAccount.create is true
and no explicit name is provided.
*/}}
{{/*
Domain, resolved from appConfig.domain or global.domain.
*/}}
{{- define "bosca-common.domain" -}}
{{- if and .Values.appConfig .Values.appConfig.domain (ne .Values.appConfig.domain "") }}
{{- .Values.appConfig.domain }}
{{- else }}
{{- .Values.global.domain }}
{{- end }}
{{- end }}

{{- define "bosca-common.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "bosca-common.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
