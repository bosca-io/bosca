package bosca.git.model

/**
 * Result of a merge attempt. Contains the resulting commit SHA on success,
 * or a list of conflicting files on failure.
 */
data class MergeResult(
    val success: Boolean,
    val mergeSha: String? = null,
    val conflictingFiles: List<String> = emptyList()
)
