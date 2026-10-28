{{- /* Keep each matcher below Envoy's default RE2 program-size limit of 100.
Only UI routes match: .git transport and raw paths remain on the API fallback. */ -}}
{{- define "bosca-git-server.uiRoutes" -}}
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
{{- $routes = append $routes "^/_bml/(libraries|js|css|i18n|identity|render|contract|action|deferred|reload|app[.]css|app[.]js)(/.*)?$" -}}
{{- $routes = append $routes "^/_git/(fonts|subscriptions)(/.*)?$" -}}
{{- toYaml $routes -}}
{{- end -}}
