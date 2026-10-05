{{/*
Default pod security context.
Override via .Values.podSecurityContext.
*/}}
{{- define "bosca-common.podSecurityContext" -}}
{{- $defaults := dict "fsGroup" 1000 "seccompProfile" (dict "type" "RuntimeDefault") -}}
{{- $merged := merge (default dict .Values.podSecurityContext) $defaults -}}
{{- toYaml $merged }}
{{- end }}

{{/*
Default container security context.
Override via .Values.securityContext.
*/}}
{{- define "bosca-common.securityContext" -}}
{{- $defaults := dict "allowPrivilegeEscalation" false -}}
{{- $merged := merge (default dict .Values.securityContext) $defaults -}}
{{- toYaml $merged }}
{{- end }}
