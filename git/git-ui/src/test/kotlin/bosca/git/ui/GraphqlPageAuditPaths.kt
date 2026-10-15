package bosca.git.ui

internal val graphqlPageAuditPaths = listOf(
    "/repositories", "/repositories?owner=all", "/repositories?owner=owner", "/repositories/repo", "/repositories/repo?file=README.md", "/repositories/repo?path=docs",
    "/repositories/repo?tab=commits", "/repositories/repo?tab=branches", "/repositories/repo?tab=tags",
    "/repositories/repo?tab=pulls", "/repositories/repo?tab=pipelines", "/repositories/repo?tab=statuses&sha=abc",
    "/repositories/repo?tab=settings", "/repositories/repo?tab=secrets",
    "/repositories/repo/settings?panel=metadata", "/repositories/repo/settings?panel=webhooks",
    "/repositories/repo/settings?panel=protection", "/repositories/repo/settings?panel=permissions", "/repositories/repo/settings?panel=utilities",
    "/repositories/repo/secrets", "/pulls", "/pulls?repo=repo&new=true", "/pulls/pull",
    "/pipelines", "/pipelines?repo=repo", "/pipelines?repo=repo&pipeline=pipeline&ref=refs%2Fheads%2Fmain", "/pipelines/run",
    "/search?repo=repo&q=snippet", "/search?repo=repo&q=paths&mode=paths",
    "/compare?repo=repo&base=main&head=feature%2Fone", "/statuses?repo=repo&sha=abc",
    "/ci-agents", "/ci-agents/agent", "/ci-agents/orchestrator",
    "/repositories/repo/github", "/repositories/repo/github?view=pulls", "/repositories/repo/github?view=deliveries", "/settings/github",
)
