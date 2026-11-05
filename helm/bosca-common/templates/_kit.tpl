{{/* Standard Kit configuration is shared by API, Runner, and the controller's JobProfile. */}}
{{- define "bosca-common.kitRuntime" -}}
{{- $kit := dig "kit" dict (.Values.global | default dict) -}}
{{- $family := $kit.harness | default "codex" -}}
{{- if not (has $family (list "codex" "claude")) }}{{ fail "global.kit.harness must be codex or claude" }}{{ end -}}
{{- $catalog := (index .Subcharts "bosca-common").Files.Get "files/kit-images.json" | fromJson -}}
{{- $runtime := mergeOverwrite (deepCopy (index $catalog $family | default dict)) ($kit.runtime | default dict) -}}
{{- $image := required "Use a released chart with bundled Kit image metadata, or set global.kit.runtime.image" $runtime.image -}}
{{- if .Values.global.imageRegistry }}{{ $image = printf "%s/%s" (trimSuffix "/" .Values.global.imageRegistry) (last (splitList "/" $image)) }}{{ end -}}
{{- $_ := set $runtime "image" $image -}}
{{- toYaml $runtime -}}
{{- end -}}

{{- define "bosca-common.kitProfile" -}}
{{- $kit := dig "kit" dict (.Values.global | default dict) -}}
{{- $family := $kit.harness | default "codex" -}}
{{- $runtime := include "bosca-common.kitRuntime" . | fromYaml -}}
{{- $serverName := include "bosca-common.fullname" (dict "Chart" (dict "Name" "bosca-server") "Values" dict "Release" .Release) -}}
{{- $scopes := list "content:view" "content:edit" "collections:view" "collections:edit" "search:read" "analytics:view" "analytics:execute" "analytics:sql" "scripts:view" "scripts:execute" "scripts:manage" "pipelines:view" "pipelines:edit" "pipelines:execute" "git:read" -}}
{{- $profile := dict "profile" (printf "kit-%s" $family) "infrastructure" "KUBERNETES"
  "image" $runtime.image "imageId" (required "Kit image metadata requires imageId" $runtime.imageId)
  "versions" (required "Kit image metadata requires versions" $runtime.versions)
  "endpointTemplate" "http://{job}.{namespace}.svc:8080"
  "boscaEndpoint" (default (printf "http://%s.%s.svc:8080/graphql" $serverName .Release.Namespace) $kit.boscaEndpoint)
  "authenticationMethod" (ternary "api-key" nil (eq $family "codex")) "scopes" $scopes -}}
{{- if dig "egress" "enabled" false $kit -}}
{{- $_ := set $profile "egressProxy" (printf "http://%s.%s.svc:3128" (include "bosca-common.kitEgressName" .) .Release.Namespace) -}}
{{- $_ := set $profile "egressHosts" (ternary (list "api.openai.com:443") (list "api.anthropic.com:443") (eq $family "codex")) -}}
{{- end -}}
{{- toYaml (mergeOverwrite $profile ($kit.profile | default dict)) -}}
{{- end -}}

{{/* Service and Deployment name of the umbrella chart's Kit egress proxy. */}}
{{- define "bosca-common.kitEgressName" -}}
{{- include "bosca-common.fullname" (dict "Chart" (dict "Name" "kit-egress") "Values" dict "Release" .Release) -}}
{{- end -}}

{{- define "bosca-common.kitConfiguration" -}}
{{- $kit := dig "kit" dict (.Values.global | default dict) -}}
{{- if $kit.enabled }}
kit:
  harness:
    maxRuntimes: {{ $kit.maxRuntimes | default 10 }}
    maxRuntimesPerPrincipal: {{ $kit.maxRuntimesPerPrincipal | default 2 }}
    maxArchiveBytesPerPrincipal: {{ $kit.maxArchiveBytesPerPrincipal | default 68719476736 }}
    profiles:
      - {{ include "bosca-common.kitProfile" . | nindent 8 | trim }}
{{- end -}}
{{- end -}}
