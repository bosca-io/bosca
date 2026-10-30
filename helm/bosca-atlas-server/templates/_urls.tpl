{{- define "bosca-atlas-server.atlasUrl" -}}
{{- .Values.appConfig.atlasUrl | default (printf "https://atlas.%s" (include "bosca-common.domain" .)) | trimSuffix "/" -}}
{{- end -}}
{{- define "bosca-atlas-server.gitUrl" -}}
{{- .Values.appConfig.gitUrl | default (printf "https://git.%s" (include "bosca-common.domain" .)) | trimSuffix "/" -}}
{{- end -}}

{{- define "bosca-atlas-server.artifactsEndpoint" -}}
{{- if .Values.appConfig.artifactsEndpoint -}}
{{- .Values.appConfig.artifactsEndpoint -}}
{{- else if .Values.backends.artifacts.serviceName -}}
{{- printf "http://%s:%v" .Values.backends.artifacts.serviceName .Values.backends.artifacts.port -}}
{{- end -}}
{{- end -}}
