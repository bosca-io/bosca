package bosca.cli.swarm

/** Matches Git UI pages and BML endpoints while leaving transport/API paths on the API listener. */
internal fun gitUiPathPattern(): String {
    val id = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
    return listOf(
        "/",
        "/(login|welcome|404|500|compare|search|statuses|robots[.]txt|llms[.]txt|sitemap[.]xml|favicon[.]ico)/?",
        "/auth/(signup|forgot|verify|reset-password|link/confirm)/?",
        "/settings/github/?",
        "/repositories(/$id(/(settings|secrets|github))?)?/?",
        "/(pulls|pipelines|ci-agents)(/$id)?/?",
        "/_bml/(libraries|js|css|i18n|identity|render|contract|action|deferred|reload|app[.]css|app[.]js)(/.*)?",
        "/_git/(fonts|subscriptions)(/.*)?",
    ).joinToString("|", prefix = "^(", postfix = ")$")
}
