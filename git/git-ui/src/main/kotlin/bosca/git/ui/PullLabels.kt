package bosca.git.ui

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

internal fun pullFileChangeLabel(change: String): String = when (change) {
    "ADD" -> "Added"
    "DELETE" -> "Deleted"
    "MODIFY" -> "Modified"
    "RENAME" -> "Renamed"
    "COPY" -> "Copied"
    else -> change.lowercase().replaceFirstChar(Char::uppercase)
}
