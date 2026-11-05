{{/* Whether the umbrella renders the Kit egress proxy and the Kit network policies. */}}
{{- define "bosca-services.kitEgressEnabled" -}}
{{- $kit := dig "kit" dict (.Values.global | default dict) -}}
{{- if and $kit.enabled (dig "egress" "enabled" false $kit) }}true{{ end -}}
{{- end -}}

{{/* Standard Bosca labels of the Kit egress proxy, named kit-egress within this release. */}}
{{- define "bosca-services.kitEgressLabels" -}}
{{- include "bosca-common.labels" (dict "Chart" .Chart "Values" (dict "nameOverride" "kit-egress") "Release" .Release) -}}
{{- end -}}

{{/* Selector labels of the Kit egress proxy Pods. */}}
{{- define "bosca-services.kitEgressSelectorLabels" -}}
{{- include "bosca-common.selectorLabels" (dict "Chart" .Chart "Values" (dict "nameOverride" "kit-egress") "Release" .Release) -}}
{{- end -}}

{{/*
Every hostname the shared Gateway listens on, aggregated as in gateway.yaml.
The egress proxy always permits these Bosca entry points on port 443.
*/}}
{{- define "bosca-services.gatewayHostnames" -}}
{{- $hostnames := list -}}
{{- range $chart := list "bosca-server" "bosca-runner" "bosca-studio" "bosca-collector" "bml-message-server" "bosca-kubernetes-controller" "bosca-artifacts-server" "bosca-git-server" "bosca-gateway" "bosca-io" "notifications-web" "profiles-web" }}
  {{- $cfg := index $.Values $chart }}
  {{- if and $cfg (kindIs "map" $cfg) }}
    {{- with index $cfg "gateway" }}
      {{- if and .enabled .hostnames }}
        {{- $hostnames = concat $hostnames .hostnames }}
      {{- end }}
    {{- end }}
  {{- end }}
{{- end }}
{{- with .Values.gateway.extraHostnames }}
  {{- $hostnames = concat $hostnames . }}
{{- end }}
{{- toJson (uniq $hostnames) -}}
{{- end -}}

{{/*
Pods other than bosca-server that serve the publicly routed Bosca hosts, with the named container ports
their HTTPRoutes reach. bosca-server has its own rule for its single listener (8080).
Each entry is {"labels": selector labels, "ports": [port names]}.
*/}}
{{- define "bosca-services.kitEgressRoutedPods" -}}
{{- $routed := list -}}
{{- $ports := dict "bosca-collector" (list "http") "bml-message-server" (list "http")
  "bosca-artifacts-server" (list "http") "bosca-git-server" (list "http" "ui") "bosca-gateway" (list "http")
  "bosca-io" (list "http") "notifications-web" (list "http") "profiles-web" (list "http") "bosca-studio" (list "http") -}}
{{- $sameOrigin := false -}}
{{- range $chart := list "bosca-studio" "bosca-io" }}
  {{- with index $.Subcharts $chart }}
    {{- $gateway := .Values.gateway | default dict }}
    {{- if and $.Values.gateway.enabled $gateway.enabled $gateway.hostnames }}
      {{- $sameOrigin = true }}
    {{- end }}
  {{- end }}
{{- end }}
{{- range $chart := keys $ports | sortAlpha }}
  {{- with index $.Subcharts $chart }}
    {{- $gateway := .Values.gateway | default dict -}}
    {{- $public := and $gateway.enabled $gateway.hostnames -}}
    {{- /* Studio and bosca.io hosts also send analytics paths to the collector. */ -}}
    {{- if and $sameOrigin (eq $chart "bosca-collector") }}{{ $public = true }}{{ end -}}
    {{- if $public }}
      {{- $routed = append $routed (dict "labels" (include "bosca-common.selectorLabels" . | fromYaml) "ports" (index $ports $chart)) }}
    {{- end }}
  {{- end }}
{{- end }}
{{- toJson $routed -}}
{{- end -}}

{{/* Kubernetes label value of the Kit JobProfile name, as the controller writes it on Kit Pods. */}}
{{- define "bosca-services.kitProfileLabel" -}}
{{- $profile := include "bosca-common.kitProfile" . | fromYaml -}}
{{- regexReplaceAll "[^A-Za-z0-9_.-]+" $profile.profile "-" | trimAll "-_." | trunc 63 | trimAll "-_." | default "job" -}}
{{- end -}}

{{/* Egress rule for cluster DNS. */}}
{{- define "bosca-services.kitDnsEgress" -}}
- to:
    - namespaceSelector: {}
      podSelector:
        matchLabels:
          k8s-app: kube-dns
  ports:
    - protocol: UDP
      port: 53
    - protocol: TCP
      port: 53
{{- end -}}
