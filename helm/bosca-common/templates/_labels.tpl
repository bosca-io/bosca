{{/*
Standard Kubernetes labels following app.kubernetes.io conventions.
*/}}
{{- define "bosca-common.labels" -}}
helm.sh/chart: {{ include "bosca-common.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: bosca
{{ include "bosca-common.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
{{- end }}

{{/*
Selector labels used for both label sets and matchLabels in deployments.
*/}}
{{- define "bosca-common.selectorLabels" -}}
app.kubernetes.io/name: {{ include "bosca-common.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
