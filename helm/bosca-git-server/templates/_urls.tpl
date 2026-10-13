{{- define "bosca-git-server.gitUrl" -}}
{{- .Values.appConfig.gitUrl | default (printf "https://git.%s" (include "bosca-common.domain" .)) | trimSuffix "/" -}}
{{- end -}}
