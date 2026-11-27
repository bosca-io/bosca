package bosca.workops.ui

import bosca.design.model.NavigationItem

val workOpsNavigationGroups = listOf(
    WorkOpsNavigationGroup("Activity", listOf(
        NavigationItem("Inbox", "/workops/inbox", false),
        NavigationItem("Tasks", "/workops/tasks", false),
        NavigationItem("Specs", "/workops/specs", false),
        NavigationItem("Filters", "/workops/filters", false),
    )),
    WorkOpsNavigationGroup("Plan", listOf(
        NavigationItem("Boards", "/workops/boards", false),
        NavigationItem("Sprints", "/workops/sprints", false),
        NavigationItem("Projects", "/workops/projects", false),
        NavigationItem("Programs", "/workops/programs", false),
        NavigationItem("Portfolios", "/workops/portfolios", false),
        NavigationItem("Milestones", "/workops/milestones", false),
    )),
    WorkOpsNavigationGroup("Deliver", listOf(
        NavigationItem("Releases", "/workops/releases", false),
        NavigationItem("Environments", "/workops/releases/environments", false),
        NavigationItem("Versions", "/workops/releases/versions", false),
    )),
    WorkOpsNavigationGroup("Settings", listOf(
        NavigationItem("Task Types", "/workops/settings/task-types", false),
        NavigationItem("Environment Types", "/workops/settings/environment-types", false),
        NavigationItem("Statuses", "/workops/settings/statuses", false),
        NavigationItem("Priorities", "/workops/settings/priorities", false),
        NavigationItem("Labels", "/workops/settings/labels", false),
        NavigationItem("Workflows", "/workops/settings/workflows", false),
        NavigationItem("Automation", "/workops/settings/automation", false),
        NavigationItem("Link Types", "/workops/settings/link-types", false),
        NavigationItem("Resolutions", "/workops/settings/resolutions", false),
        NavigationItem("SLA", "/workops/settings/sla", false),
        NavigationItem("Components", "/workops/settings/components", false),
        NavigationItem("Permissions", "/workops/settings/permissions", false),
        NavigationItem("Notifications", "/workops/settings/notifications", false),
        NavigationItem("Milestones", "/workops/settings/milestones", false),
    )),
)

/** Chooses the most specific WorkOps destination so environment/version pages do not select Releases. */
fun workOpsNavigationSelected(path: String, href: String): Boolean =
    workOpsNavigationGroups.flatMap { it.items }
        .filter { path == it.href || path.startsWith("${it.href}/") }
        .maxByOrNull { it.href.length }?.href == href
