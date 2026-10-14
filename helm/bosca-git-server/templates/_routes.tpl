{{- /* Only UI routes match: .git transport and raw paths remain on the API fallback. */ -}}
{{- define "bosca-git-server.uiRoutes" -}}
^(/|/(login|welcome|404|500|compare|search|statuses|robots[.]txt|llms[.]txt|sitemap[.]xml|favicon[.]ico)/?|/auth/(signup|forgot|verify|reset-password|link/confirm)/?|/settings/github/?|/repositories(/[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}(/(settings|secrets|github))?)?/?|/(pulls|pipelines|ci-agents)(/[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})?/?|/_bml/(js|css|i18n|identity|render|contract|action|deferred|reload|app[.]css|app[.]js)(/.*)?|/_git/(fonts|subscriptions)(/.*)?)$
{{- end -}}
