package bosca.system.ui

import bosca.design.model.NavigationItem

val systemNavigationGroups = listOf(
    SystemNavigationGroup("Operate", listOf(
        NavigationItem("Jobs", "/system/jobs", false),
        NavigationItem("Backups", "/system/backups", false),
        NavigationItem("PostgreSQL", "/system/postgres", false),
        NavigationItem("Meilisearch", "/system/meilisearch", false),
        NavigationItem("NATS", "/system/nats", false),
        NavigationItem("Utilities", "/system/utilities", false),
    )),
    SystemNavigationGroup("Security", listOf(
        NavigationItem("API tokens", "/system/security/tokens", false),
        NavigationItem("Principals", "/system/security/principals", false),
        NavigationItem("Groups", "/system/security/groups", false),
        NavigationItem("Personas", "/system/security/personas", false),
        NavigationItem("Passkeys", "/system/security/passkeys", false),
    )),
    SystemNavigationGroup("Platform", listOf(
        NavigationItem("Storage", "/system/storage", false),
        NavigationItem("Search", "/system/search", false),
        NavigationItem("Integrations", "/system/integrations", false),
        NavigationItem("Packages", "/system/packages", false),
        NavigationItem("Scheduler", "/system/scheduler", false),
        NavigationItem("Configuration", "/system/config", false),
    )),
)

val systemNavigation = systemNavigationGroups.flatMap { it.items }

fun systemNavigationSelected(path: String, href: String): Boolean = path == href || path.startsWith("$href/")

fun systemNavigationIcon(href: String): String = when (href.substringAfterLast('/')) {
    "jobs" -> "workflow"
    "backups", "postgres", "storage" -> "database"
    "meilisearch", "search" -> "search"
    "nats" -> "pulse"
    "utilities" -> "wrench"
    "tokens", "passkeys" -> "key"
    "principals", "groups" -> "users"
    "personas" -> "shield"
    "integrations" -> "globe"
    "packages" -> "package"
    "scheduler" -> "calendar"
    else -> "settings"
}

fun systemTrail(path: String): List<NavigationItem> =
    listOf(NavigationItem("System", "/system/jobs", false)) +
        if (path.startsWith("/system/integrations/")) listOf(NavigationItem("Integrations", "/system/integrations", false)) else emptyList()

fun systemSecurityTrail(section: String = "", href: String = ""): List<NavigationItem> =
    listOf(NavigationItem("System", "/system/jobs", false), NavigationItem("Security", "", false)) +
        if (section.isBlank()) emptyList() else listOf(NavigationItem(section, href, false))
