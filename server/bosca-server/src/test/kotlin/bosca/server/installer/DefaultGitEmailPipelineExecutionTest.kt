@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.server.installer

import bosca.communications.model.Message
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.service.MessageService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.GitRefKind
import bosca.git.model.GitRefUpdateAction
import bosca.git.model.PullRequestEvent
import bosca.git.model.PullRequestEventAction
import bosca.git.model.RefUpdateEvent
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineService
import bosca.pipelines.service.requireCompleted
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Executes the installed event graph through the real pipeline engine and email action node. */
class DefaultGitEmailPipelineExecutionTest {

    private val messageService = mockk<MessageService>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<MessageService>(singleton = true) { messageService }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private suspend fun gitPipeline(name: String): Pipeline {
        val captured = mutableListOf<Pipeline>()
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.getAll() } returns emptyList()
        coEvery { pipelineService.graphAsJsonElement(capture(captured)) } returns JsonObject(emptyMap())
        coEvery {
            pipelineService.save(
                id = any(), name = any(), description = any(), acceptedInputType = any(),
                triggered = any(), version = any(), graph = any(), tags = any(), key = any(),
                api = any(), public = any(), schedule = any(), maxConcurrentRuns = any(),
                maxRunsPerMinute = any(),
            )
        } returns mockk(relaxed = true)

        DefaultGitEmailPipelinesInstaller(pipelineService, "https://studio.example/")
            .install(mockk(relaxed = true), mockk(relaxed = true))
        return captured.single { it.name == name }
    }

    @Test
    fun `pull request event graph sends its recipients and typed payload`() = runTest {
        val recipientId = UUID.random()
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
            recipientIds = setOf(recipientId),
            actorId = UUID.random(),
            actorName = "Maya Rivera",
            taskKeys = setOf("GIT-79"),
            body = "Please preserve the task context.",
            filePath = "src/Git.kt",
            lineNumber = 42,
        )

        PipelineExecutorImpl().execute(
            gitPipeline(DefaultGitEmailPipelinesInstaller.PULL_REQUEST_PIPELINE),
            PipelineValue.of(event, PullRequestEvent.serializer()),
            PipelineContext(AuthenticationContext(null, null), json),
        ).requireCompleted()

        coVerify(exactly = 1) {
            messageService.send(match<Message> { message ->
                val template = message.bmlTemplate ?: return@match false
                val payload = template.payload?.jsonObject ?: return@match false
                message.recipients == listOf(recipientId) &&
                    message.type == NotificationTypeKeys.GIT_ACTIVITY &&
                    template.project == "bosca-messages" &&
                    template.templateKey == "git-pull-request" &&
                    payload["actorName"]?.jsonPrimitive?.content == "Maya Rivera" &&
                    payload["pullRequestUrl"]?.jsonPrimitive?.content ==
                    "https://studio.example/git/pulls/$pullRequestId?repo=$repositoryId&number=79"
            })
        }
    }

    @Test
    fun `ref update event graph sends its recipients and typed payload`() = runTest {
        val recipientId = UUID.random()
        val repositoryId = UUID.random()
        val event = RefUpdateEvent(
            repositoryId = repositoryId,
            repositoryName = "Bosca",
            ref = "refs/tags/v1.0.0",
            refName = "v1.0.0",
            kind = GitRefKind.TAG,
            action = GitRefUpdateAction.CREATED,
            afterSha = "a".repeat(40),
            recipientIds = setOf(recipientId),
            taskKeys = setOf("GIT-79"),
            commitMessages = listOf("Release Git email notifications"),
        )

        PipelineExecutorImpl().execute(
            gitPipeline(DefaultGitEmailPipelinesInstaller.REF_UPDATE_PIPELINE),
            PipelineValue.of(event, RefUpdateEvent.serializer()),
            PipelineContext(AuthenticationContext(null, null), json),
        ).requireCompleted()

        coVerify(exactly = 1) {
            messageService.send(match<Message> { message ->
                val template = message.bmlTemplate ?: return@match false
                val payload = template.payload?.jsonObject ?: return@match false
                message.recipients == listOf(recipientId) &&
                    message.type == NotificationTypeKeys.GIT_ACTIVITY &&
                    template.project == "bosca-messages" &&
                    template.templateKey == "git-ref-update" &&
                    payload["refName"]?.jsonPrimitive?.content == "v1.0.0" &&
                    payload["repositoryUrl"]?.jsonPrimitive?.content ==
                    "https://studio.example/git/repositories/$repositoryId"
            })
        }
    }

    @Test
    fun `empty recipient events stop at the condition`() = runTest {
        val event = PullRequestEvent(
            repositoryId = UUID.random(),
            repositoryName = "Bosca",
            pullRequestId = UUID.random(),
            number = 1,
            action = PullRequestEventAction.OPENED,
            title = "No audience",
            sourceBranch = "feature/no-audience",
            targetBranch = "main",
            authorId = UUID.random(),
        )

        PipelineExecutorImpl().execute(
            gitPipeline(DefaultGitEmailPipelinesInstaller.PULL_REQUEST_PIPELINE),
            PipelineValue.of(event, PullRequestEvent.serializer()),
            PipelineContext(AuthenticationContext(null, null), json),
        ).requireCompleted()

        coVerify(exactly = 0) { messageService.send(any()) }
    }
}
