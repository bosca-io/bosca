{{/*
Container image reference. Supports both tag and digest-based pinning.
Usage: {{ include "bosca-common.image" .Values.image }}

Expects a map with:
  repository: string (required)
  tag: string (optional, defaults to "latest")
  digest: string (optional, takes precedence over tag)
*/}}
{{- define "bosca-common.image" -}}
{{- if .digest }}
{{- printf "%s@%s" .repository .digest }}
{{- else }}
{{- printf "%s:%s" .repository (default "latest" .tag) }}
{{- end }}
{{- end }}

{{/*
Bosca-built image reference, served from the deployment's image registry.
Usage: {{ include "bosca-common.boscaImage" (dict "image" .Values.image "global" .Values.global) }}

The image map is as for bosca-common.image, with `registry` naming the registry
(and path) and `repository` naming the image within it. global.imageRegistry,
when set, overrides image.registry; rendering fails when neither is set.
*/}}
{{- define "bosca-common.boscaImage" -}}
{{- $registry := dig "imageRegistry" "" (.global | default dict) | default .image.registry | required "Set image.registry (or global.imageRegistry) to the registry that hosts Bosca images" -}}
{{- include "bosca-common.image" (merge (dict "repository" (printf "%s/%s" (trimSuffix "/" $registry) .image.repository)) .image) -}}
{{- end }}

{{/*
Image pull policy. Defaults to IfNotPresent.
Usage: {{ include "bosca-common.imagePullPolicy" .Values.image }}
*/}}
{{- define "bosca-common.imagePullPolicy" -}}
{{- default "IfNotPresent" .pullPolicy }}
{{- end }}
