package bosca.workops.ui

/** An assigned but inaccessible profile must not be presented as an unassigned task. */
fun workOpsAssigneeLabel(profileId: String?, name: String?): String =
    if (profileId.isNullOrBlank()) "Unassigned" else name?.takeIf(String::isNotBlank) ?: "Profile unavailable"
