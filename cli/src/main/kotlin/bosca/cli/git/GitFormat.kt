package bosca.cli.git

import bosca.graphql.gen.GitMergeStrategy
import bosca.graphql.gen.GitPullRequestStatus
import bosca.graphql.gen.GitRepositoryContentType

/** Presentation helpers shared across the git commands. */
object GitFormat {

    /** Formats a byte count as a human-readable size (e.g. `3.4 MB`). */
    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble() / 1024
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024
            i++
        }
        return String.format("%.1f %s", value, units[i])
    }

    /** The content-type names a user may pass on the command line. */
    fun contentTypeNames(): List<String> =
        GitRepositoryContentType.entries.map { it.name }

    /**
     * Parses a `--content-type` value into its enum, case-insensitively.
     * Throws [IllegalArgumentException] listing the valid names when the
     * value is unrecognised.
     */
    fun parseContentType(value: String): GitRepositoryContentType =
        GitRepositoryContentType.entries.find { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Unknown content type '$value'. Valid values: ${contentTypeNames().joinToString(", ")}",
            )

    /** The pull-request status names a user may filter by. */
    fun pullRequestStatusNames(): List<String> =
        GitPullRequestStatus.entries.map { it.name }

    /** Parses a `--status` value into its enum, case-insensitively. */
    fun parsePullRequestStatus(value: String): GitPullRequestStatus =
        GitPullRequestStatus.entries.find { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Unknown status '$value'. Valid values: ${pullRequestStatusNames().joinToString(", ")}",
            )

    /** The merge-strategy names a user may pass to `pr merge`. */
    fun mergeStrategyNames(): List<String> =
        GitMergeStrategy.entries.map { it.name }

    /** Parses a `--strategy` value into its enum, case-insensitively. */
    fun parseMergeStrategy(value: String): GitMergeStrategy =
        GitMergeStrategy.entries.find { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Unknown merge strategy '$value'. Valid values: ${mergeStrategyNames().joinToString(", ")}",
            )
}
