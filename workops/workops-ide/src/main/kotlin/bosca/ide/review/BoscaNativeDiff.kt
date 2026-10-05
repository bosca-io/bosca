package bosca.ide.review

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.awt.Color
import javax.swing.Icon

internal data class BoscaDiffReviewData(
    val filePath: String,
    val comments: List<BoscaReviewComment>,
)

/** Builds a native IntelliJ diff chain, retaining syntax highlighting and standard diff navigation. */
internal object BoscaNativeDiff {
    val REVIEW_DATA: Key<BoscaDiffReviewData> = Key.create("bosca.diff.review.data")

    fun open(project: Project, detail: BoscaPullRequestDetail, contents: List<BoscaDiffContents>, initialIndex: Int) {
        val factory = DiffContentFactory.getInstance()
        val requests = contents.map { value ->
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(value.file.path)
            val oldText = if (value.binary) "Binary file — textual content is not available" else value.oldText
            val newText = if (value.binary) "Binary file — textual content is not available" else value.newText
            SimpleDiffRequest(
                "#${detail.summary.number} · ${value.file.path} · ${value.file.changeType}",
                factory.create(project, oldText, fileType),
                factory.create(project, newText, fileType),
                value.file.oldPath ?: "/dev/null",
                value.file.newPath ?: "/dev/null",
            ).apply {
                putUserData(
                    REVIEW_DATA,
                    BoscaDiffReviewData(
                        value.file.path,
                        detail.reviews.flatMap { it.comments }.filter { it.filePath == value.file.path },
                    ),
                )
            }
        }
        if (requests.isEmpty()) return
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequestChain(requests, initialIndex.coerceIn(requests.indices)),
            DiffDialogHints.DEFAULT,
        )
    }
}

/** Marks exact current old/new Bosca review anchors inside native two-sided diff editors. */
class BoscaDiffReviewExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: com.intellij.diff.DiffContext, request: DiffRequest) {
        val data = request.getUserData(BoscaNativeDiff.REVIEW_DATA) ?: return
        val twoSided = viewer as? TwosideTextDiffViewer ?: return
        data.comments.filterNot { it.outdated }.forEach { comment ->
            val side = if (comment.newLineNumber != null) Side.RIGHT else Side.LEFT
            val oneBasedLine = comment.newLineNumber ?: comment.oldLineNumber ?: return@forEach
            val editor = twoSided.getEditor(side)
            val zeroBasedLine = oneBasedLine - 1
            if (zeroBasedLine !in 0 until editor.document.lineCount) return@forEach
            val start = editor.document.getLineStartOffset(zeroBasedLine)
            val end = editor.document.getLineEndOffset(zeroBasedLine)
            val attributes = TextAttributes().apply {
                backgroundColor = if (comment.resolved) Color(100, 100, 100, 24) else Color(73, 156, 84, 36)
            }
            editor.markupModel.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                attributes,
                HighlighterTargetArea.EXACT_RANGE,
            ).apply {
                errorStripeTooltip = buildString {
                    append(if (comment.resolved) "Resolved review thread" else "Open review thread")
                    append(": ").append(comment.content)
                }
                gutterIconRenderer = ReviewGutterIcon(comment)
            }
        }
    }

    private class ReviewGutterIcon(private val comment: BoscaReviewComment) : GutterIconRenderer() {
        override fun getIcon(): Icon = if (comment.resolved) AllIcons.General.InspectionsOK else AllIcons.General.Note
        override fun getTooltipText(): String = comment.content
        override fun equals(other: Any?): Boolean = other is ReviewGutterIcon && other.comment.id == comment.id
        override fun hashCode(): Int = comment.id.hashCode()
    }
}
