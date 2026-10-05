package bosca.comments.pipeline

import ai.koog.prompt.dsl.ModerationCategory
import ai.koog.prompt.dsl.ModerationCategoryResult
import ai.koog.prompt.dsl.ModerationResult
import bosca.comments.model.CommentStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Logic of the moderation pipeline nodes — pure, so no engine/HTTP/container needed. */
class CommentModerationNodesTest {

    private fun moderation(harmful: Boolean, vararg cats: Pair<ModerationCategory, Pair<Boolean, Double?>>) =
        ModerationResult(
            isHarmful = harmful,
            categories = cats.associate { (cat, dr) -> cat to ModerationCategoryResult(detected = dr.first, confidenceScore = dr.second) },
        )

    // --- Evaluate Text Moderation: verdict mapping ---

    @Test
    fun `harmful with high confidence blocks`() =
        assertEquals(EvaluationVerdict.BLOCKED, moderationVerdict(moderation(true, ModerationCategory.Hate to (true to 0.9)), 0.1, 0.5))

    @Test
    fun `harmful with low confidence flags`() =
        assertEquals(EvaluationVerdict.FLAGGED, moderationVerdict(moderation(true, ModerationCategory.Harassment to (true to 0.2)), 0.1, 0.5))

    @Test
    fun `not harmful but at or above flag threshold flags`() =
        assertEquals(EvaluationVerdict.FLAGGED, moderationVerdict(moderation(false, ModerationCategory.Violence to (false to 0.3)), 0.1, 0.5))

    @Test
    fun `not harmful and below flag threshold approves`() =
        assertEquals(EvaluationVerdict.APPROVED, moderationVerdict(moderation(false, ModerationCategory.Violence to (false to 0.05)), 0.1, 0.5))

    @Test
    fun `no categories approves`() =
        assertEquals(EvaluationVerdict.APPROVED, moderationVerdict(moderation(false), 0.1, 0.5))

    // --- Verdict → Comment Status ---

    @Test
    fun `verdict maps to comment status`() {
        assertEquals(CommentStatus.APPROVED, verdictToCommentStatus(EvaluationVerdict.APPROVED))
        assertEquals(CommentStatus.PENDING_APPROVAL, verdictToCommentStatus(EvaluationVerdict.FLAGGED))
        assertEquals(CommentStatus.BLOCKED, verdictToCommentStatus(EvaluationVerdict.BLOCKED))
    }
}
