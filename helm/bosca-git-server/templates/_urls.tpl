{{- define "bosca-git-server.gitUrl" -}}
{{- .Values.appConfig.gitUrl | default (printf "https://git.%s" (include "bosca-common.domain" .)) | trimSuffix "/" -}}
{{- end -}}

{{- define "bosca-git-server.uiUrl" -}}
{{- .Values.appConfig.uiUrl | default (printf "https://atlas.%s" (include "bosca-common.domain" .)) | trimSuffix "/" -}}
{{- end -}}
