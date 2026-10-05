package bosca.ide.review

data class BoscaPullRequestSummary(
    val serverProfileId: String,
    val repositoryId: String,
    val number: Int,
    val id: String,
    val title: String,
    val status: String,
    val authorId: String,
    val sourceBranch: String,
    val targetBranch: String,
    val mergeable: Boolean,
    val updated: String,
)

data class BoscaPullRequestDetail(
    val summary: BoscaPullRequestSummary,
    val description: String,
    val assignees: List<BoscaPullRequestAssignee>,
    val dependencies: List<String>,
    val dependents: List<String>,
    val conflictingFiles: List<String>,
    val reviews: List<BoscaReview>,
    val files: List<BoscaDiffFile>,
    val headSha: String?,
    val checks: List<BoscaCommitCheck>,
    val pipelineRuns: List<BoscaPullRequestPipelineRun>,
    val mergeStrategies: List<String>,
)

data class BoscaPullRequestAssignee(val id: String, val name: String) {
    override fun toString(): String = name
}

data class BoscaReview(
    val id: String,
    val reviewerId: String,
    val status: String,
    val body: String?,
    val created: String,
    val dismissedAt: String?,
    val comments: List<BoscaReviewComment>,
)

data class BoscaReviewComment(
    val id: String,
    val reviewId: String,
    val authorId: String,
    val filePath: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val commitSha: String,
    val content: String,
    val outdated: Boolean,
    val resolved: Boolean,
    val created: String,
)

data class BoscaDiffFile(
    val oldPath: String?,
    val newPath: String?,
    val changeType: String,
    val hunks: List<BoscaDiffHunk>,
) {
    val path: String get() = newPath ?: oldPath.orEmpty()
}

data class BoscaDiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<BoscaDiffLine>,
)

data class BoscaDiffLine(
    val type: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val content: String,
)

data class BoscaCommitCheck(val context: String, val state: String, val description: String?)

data class BoscaPullRequestPipelineRun(val number: Int, val status: String, val ref: String)

data class BoscaDiffContents(
    val file: BoscaDiffFile,
    val oldText: String,
    val newText: String,
    val binary: Boolean,
)

enum class BoscaDiffSide { OLD, NEW }

data class BoscaReviewAnchor(val oldLine: Int?, val newLine: Int?)

/** Refuses anchors that are not present in the server diff, preventing comments from drifting after force-pushes. */
internal object BoscaReviewAnchors {
    fun find(file: BoscaDiffFile, side: BoscaDiffSide, oneBasedLine: Int): BoscaReviewAnchor? {
        if (oneBasedLine < 1) return null
        val line = file.hunks.asSequence().flatMap { it.lines.asSequence() }.firstOrNull {
            when (side) {
                BoscaDiffSide.OLD -> it.oldLineNumber == oneBasedLine
                BoscaDiffSide.NEW -> it.newLineNumber == oneBasedLine
            }
        } ?: return null
        return BoscaReviewAnchor(line.oldLineNumber, line.newLineNumber)
    }
}
