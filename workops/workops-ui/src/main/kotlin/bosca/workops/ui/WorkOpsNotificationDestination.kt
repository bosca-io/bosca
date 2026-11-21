package bosca.workops.ui

private val approvalNotificationPath = Regex("/pipelines/runs\\?runId=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")

/** Resolves existing platform approval notification links without changing stored links or Git CI identities. */
fun workOpsNotificationDestination(event: String, link: String?, taskId: String?, projectId: String?): String {
    if (link != null && link.startsWith('/') && !link.startsWith("//")) {
        if (event == "PIPELINE_APPROVAL_REQUESTED") approvalNotificationPath.matchEntire(link)?.let {
            return "/workops/approvals/${it.groupValues[1]}"
        }
        return link
    }
    if (taskId != null) return "/workops/tasks/${workOpsEncode(taskId)}"
    if (projectId != null) return "/workops/projects/${workOpsEncode(projectId)}"
    return ""
}
