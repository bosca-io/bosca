package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GitEventTest {

    @Test
    fun `pull request event serializes notification detail and scopes identity by action`() {
        val repositoryId = UUID.random()
        val pullRequestId = UUID.random()
        val event = PullRequestEvent(
            repositoryId = repositoryId,
            repositoryName = "Bosca",
            pullRequestId = pullRequestId,
            number = 79,
            action = PullRequestEventAction.COMMENTED,
            title = "Send Git email",
            sourceBranch = "feature/GIT-79-email",
            targetBranch = "main",
            authorId = UUID.random(),
            recipientIds = setOf(UUID.random()),
            body = "Please cover nested branches.",
            filePath = "src/Git.kt",
            lineNumber = 42,
            taskKeys = setOf("GIT-79"),
        )

        val decoded = Json.decodeFromString(
            PullRequestEvent.serializer(),
            Json.encodeToString(PullRequestEvent.serializer(), event),
        )

        assertEquals(event, decoded)
        assertEquals("pull-request:$pullRequestId:COMMENTED", event.identityKey())
        assertNotEquals(event.identityKey(), event.copy(action = PullRequestEventAction.UPDATED).identityKey())
    }

    @Test
    fun `pull request event decodes payloads created before repository name was added`() {
        val event = PullRequestEvent(
            repositoryId = UUID.random(),
            pullRequestId = UUID.random(),
            number = 12,
            action = PullRequestEventAction.OPENED,
            title = "Legacy payload",
            sourceBranch = "feature/legacy",
            targetBranch = "main",
            authorId = UUID.random(),
        )
        val encoded = Json.encodeToJsonElement(PullRequestEvent.serializer(), event).jsonObject
        val legacyPayload = JsonObject(encoded - "repositoryName")

        val decoded = Json.decodeFromJsonElement(PullRequestEvent.serializer(), legacyPayload)

        assertEquals("Repository", decoded.repositoryName)
        assertEquals(event.pullRequestId, decoded.pullRequestId)
    }

    @Test
    fun `ref update event serializes nested branch name and scopes identity by operation`() {
        val repositoryId = UUID.random()
        val event = RefUpdateEvent(
            repositoryId = repositoryId,
            repositoryName = "Bosca",
            ref = "refs/heads/feature/GIT-79-email",
            refName = "feature/GIT-79-email",
            kind = GitRefKind.BRANCH,
            action = GitRefUpdateAction.UPDATED,
            beforeSha = "a".repeat(40),
            afterSha = "b".repeat(40),
            recipientIds = setOf(UUID.random()),
            taskKeys = setOf("GIT-79"),
            commitMessages = listOf("GIT-79 send notifications"),
        )

        val decoded = Json.decodeFromString(
            RefUpdateEvent.serializer(),
            Json.encodeToString(RefUpdateEvent.serializer(), event),
        )

        assertEquals(event, decoded)
        assertEquals("ref-update:$repositoryId:${event.ref}:UPDATED", event.identityKey())
        assertNotEquals(event.identityKey(), event.copy(action = GitRefUpdateAction.DELETED).identityKey())
    }
}
