{{- /* Keep each matcher below Envoy's default RE2 program-size limit of 100.
Only UI routes match: .git transport and raw paths remain on the API fallback. */ -}}
{{- define "bosca-atlas-server.uiRoutes" -}}
{{- $uuid := "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}" -}}
{{- $routes := list
  "^/$"
  "^/(login|welcome|404|500|compare|search|statuses)/?$"
  "^/(robots[.]txt|llms[.]txt|sitemap[.]xml|favicon[.](ico|svg))/?$"
  "^/auth/(signup|forgot|verify|reset-password|link/confirm)/?$"
  "^/settings/github/?$"
  "^/(repositories|pulls|pipelines|ci-agents)/?$"
-}}
{{- range list "" "/settings" "/secrets" "/github" -}}
  {{- $routes = append $routes (printf "^/repositories/%s%s/?$" $uuid .) -}}
{{- end -}}
{{- range list "pulls" "pipelines" "ci-agents" -}}
  {{- $routes = append $routes (printf "^/%s/%s/?$" . $uuid) -}}
{{- end -}}
{{- $routes = append $routes "^/_bml/(js|css|i18n|identity|render|contract|action|deferred|reload|app[.]css|app[.]js)(/.*)?$" -}}
{{- $routes = append $routes "^/_git/(fonts|subscriptions)(/.*)?$" -}}
{{- $routes = append $routes (printf "^/artifacts/namespaces(/%s)?/?$" $uuid) -}}
{{- $routes = append $routes "^/artifacts/repositories(/upload)?/?$" -}}
{{- $routes = append $routes (printf "^/artifacts/repositories/%s/?$" $uuid) -}}
{{- $routes = append $routes "^/artifacts/settings/permissions/?$" -}}
{{- $routes = append $routes (printf "^/artifacts/settings/repositories/%s/?$" $uuid) -}}
{{- $routes = append $routes (printf "^/artifacts/runs/%s/?$" $uuid) -}}
{{- $routes = append $routes "^/_artifacts(/.*)?$" -}}
{{- toYaml $routes -}}
{{- end -}}

{{- /* Keep each matcher below Envoy's default RE2 program-size limit of 100.
Only UI routes match: .git transport and raw paths remain on the API fallback. */ -}}
{{- define "bosca-atlas-server.gitUiRoutes" -}}
{{- $uuid := "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}" -}}
{{- $routes := list
  "^/$"
  "^/(login|welcome|404|500|compare|search|statuses)/?$"
  "^/(robots[.]txt|llms[.]txt|sitemap[.]xml|favicon[.]ico)/?$"
  "^/auth/(signup|forgot|verify|reset-password|link/confirm)/?$"
  "^/settings/github/?$"
  "^/(repositories|pulls|pipelines|ci-agents)/?$"
-}}
{{- range list "" "/settings" "/secrets" "/github" -}}
  {{- $routes = append $routes (printf "^/repositories/%s%s/?$" $uuid .) -}}
{{- end -}}
{{- range list "pulls" "pipelines" "ci-agents" -}}
  {{- $routes = append $routes (printf "^/%s/%s/?$" . $uuid) -}}
{{- end -}}
{{- $routes = append $routes "^/_bml/(js|css|i18n|identity|render|contract|action|deferred|reload|app[.]css|app[.]js)(/.*)?$" -}}
{{- $routes = append $routes "^/_git/(fonts|subscriptions)(/.*)?$" -}}
{{- toYaml $routes -}}
{{- end -}}

{{- define "bosca-atlas-server.gitBackend" -}}
{{- if .Values.backends.git.serviceName -}}
{{- toYaml (dict "name" .Values.backends.git.serviceName "port" .Values.backends.git.port) -}}
{{- else -}}
{{- toYaml (dict "name" (include "bosca-common.fullname" .) "port" .Values.service.port) -}}
{{- end -}}
{{- end -}}
{{- define "bosca-atlas-server.artifactsBackend" -}}
{{- if .Values.backends.artifacts.serviceName -}}
{{- toYaml (dict "name" .Values.backends.artifacts.serviceName "port" .Values.backends.artifacts.port) -}}
{{- else -}}
{{- toYaml (dict "name" (include "bosca-common.fullname" .) "port" .Values.service.port) -}}
{{- end -}}
{{- end -}}
