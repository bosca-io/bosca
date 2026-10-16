package bosca.git.ui

fun reviewDismissReasonLabel(reason: String): String = when (reason) {
    "branches_updated" -> "Branches changed"
    "source_updated" -> "Source branch updated"
    else -> reason
}

internal fun reviewStatusLabel(status: String): String = when (status) {
    "APPROVED" -> "Approved"
    "CHANGES_REQUESTED" -> "Changes requested"
    "COMMENT_ONLY" -> "Commented"
    else -> status.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

internal fun mergeStrategyLabel(strategy: String): String = when (strategy) {
    "MERGE_COMMIT" -> "Merge commit"
    "SQUASH" -> "Squash and merge"
    "REBASE" -> "Rebase and merge"
    "FAST_FORWARD" -> "Fast-forward"
    else -> strategy.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

/** Merge method choices as Studio labels them in the pull-request Actions card. */
internal fun mergeStrategyOptionLabel(strategy: String): String = when (strategy) {
    "MERGE_COMMIT" -> "Merge Commit"
    "FAST_FORWARD" -> "Fast Forward"
    else -> strategy.lowercase().replaceFirstChar(Char::uppercase)
}

internal fun pullFileChangeLabel(change: String): String = when (change) {
    "ADD" -> "Added"
    "DELETE" -> "Deleted"
    "MODIFY" -> "Modified"
    "RENAME" -> "Renamed"
    "COPY" -> "Copied"
    else -> change.lowercase().replaceFirstChar(Char::uppercase)
}

/** Studio's pull-request lifecycle colours. */
internal fun pullStatusColor(status: String): String = when (status) {
    "OPEN" -> "#34d99a"
    "MERGED" -> "#a78bff"
    "CLOSED" -> "#ff5d6c"
    else -> "#6c7388"
}

/** Studio's review-state colours. */
internal fun reviewStatusColor(status: String): String = when (status) {
    "APPROVED" -> "#34d99a"
    "CHANGES_REQUESTED" -> "#ff5d6c"
    "COMMENT_ONLY" -> "#ffb547"
    else -> "#6c7388"
}

/** Studio's changed-file badge colours. */
internal fun pullFileChangeColor(change: String): String = when (change) {
    "ADD" -> "#34d99a"
    "DELETE" -> "#ff5d6c"
    "MODIFY" -> "#ffb547"
    else -> "#5ec5ff"
}
