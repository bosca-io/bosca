{{/*
Common annotations applied to all resources.
Includes Helm metadata and any user-supplied annotations from .Values.commonAnnotations.
*/}}
{{- define "bosca-common.annotations" -}}
helm.sh/revision: {{ .Release.Revision | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- with .Values.commonAnnotations }}
{{ toYaml . }}
{{- end }}
{{- end }}
