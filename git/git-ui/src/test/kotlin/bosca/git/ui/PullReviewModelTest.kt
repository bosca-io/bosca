package bosca.git.ui

import bosca.git.ui.graphql.GitPullRequestStatus
import bosca.git.ui.graphql.GitUiPullData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.serialization.json.Json

class PullReviewModelTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val pull = json.decodeFromString(
        GitUiPullData.Git.PullRequest.serializer(),
        requireNotNull(javaClass.getResource("/fixtures/pull.json")).readText(),
    )
    private val page = PullPage(
        pull, "/pulls/pull", "", "abc", listOf("owner"), "", emptyList(), emptyList(),
        emptyMap(), true, 0, "files", false,
    )

    @Test
    fun `line discussions are reused and refreshed when reviews change`() {
        val model = PullReviewModel(page)
        val first = model.lineThreads("a.txt", null, 1).single()
        assertSame(first, model.lineThreads("a.txt", null, 1).single())
        assertEquals(emptyList(), model.lineThreads("other.txt", null, 1))
        assertEquals(emptyList(), model.lineThreads("a.txt", 1, null))
        val review = pull.reviews.single()
        val comment = review.comments.single()
        model.reviews = listOf(review.copy(comments = listOf(comment.copy(content = "Updated", newLineNumber = 2))))
        assertEquals(emptyList(), model.lineThreads("a.txt", null, 1))
        assertEquals("Updated", model.lineThreads("a.txt", null, 2).single().first.content)
    }

    @Test
    fun `index preserves old side matching and excludes outdated or other commit discussions`() {
        val review = pull.reviews.single()
        val comment = review.comments.single()
        val reviews = listOf(review.copy(comments = listOf(
            comment.copy(id = "old", oldLineNumber = 3, newLineNumber = null),
            comment.copy(id = "outdated", outdated = true),
            comment.copy(id = "previous", commitSha = "previous"),
        )))
        val model = PullReviewModel(page.copy(pull = pull.copy(status = GitPullRequestStatus.MERGED)), reviews = reviews)
        assertEquals(listOf("old"), model.lineThreads("a.txt", 3, 4).map { it.id })
        assertEquals(emptyList(), model.lineThreads("a.txt", 4, 3))
        assertEquals(emptyList(), model.lineThreads("a.txt", null, 1))
    }

    @Test
    fun `discussion index is rebuilt after serialized state restoration`() {
        val model = PullReviewModel(page)
        model.lineThreads("a.txt", null, 1)
        val state = json.encodeToString(PullReviewModel.serializer(), model)
        val restored = json.decodeFromString(PullReviewModel.serializer(), state)
        assertEquals(listOf("comment"), restored.lineThreads("a.txt", null, 1).map { it.id })
    }
}
