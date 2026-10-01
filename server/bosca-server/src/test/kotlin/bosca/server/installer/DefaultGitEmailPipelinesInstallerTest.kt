package bosca.server.installer

import bosca.communications.model.NotificationTypeKeys
import bosca.git.model.PullRequestEvent
import bosca.git.model.RefUpdateEvent
import bosca.pipelines.builtin.ConditionNode
import bosca.pipelines.builtin.JsonataNode
import bosca.pipelines.builtin.SendEmailTemplateNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.service.PipelineService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultGitEmailPipelinesInstallerTest {

    private fun installer(existing: List<String> = emptyList()): Pair<DefaultGitEmailPipelinesInstaller, MutableList<Pipeline>> {
        val captured = mutableListOf<Pipeline>()
        val pipelines = mockk<PipelineService>()
        coEvery { pipelines.getAll() } returns existing.map { name ->
            mockk<Pipeline> { every { this@mockk.name } returns name }
        }
        coEvery { pipelines.graphAsJsonElement(any()) } answers {
            firstArg<Pipeline>().also { pipeline ->
                if (pipeline.name !in existing) captured += pipeline
            }
            JsonObject(emptyMap())
        }
        coEvery {
            pipelines.save(
                id = any(), name = any(), description = any(), acceptedInputType = any(),
                triggered = any(), version = any(), graph = any(), tags = any(), key = any(),
                api = any(), public = any(), schedule = any(), maxConcurrentRuns = any(),
                maxRunsPerMinute = any(),
            )
        } returns mockk(relaxed = true)
        return DefaultGitEmailPipelinesInstaller(pipelines, "https://studio.example/") to captured
    }

    @Test
    fun `seeds triggered pull request and ref email pipelines`() = runTest {
        val (installer, captured) = installer()
        installer.install(mockk(relaxed = true), mockk(relaxed = true))

        assertEquals(2, captured.size)
        assertEquals(
            setOf(PullRequestEvent::class.qualifiedName, RefUpdateEvent::class.qualifiedName),
            captured.map { it.acceptedInputType }.toSet(),
        )
        assertTrue(captured.all { it.triggered })
        assertTrue(captured.all { it.tags == listOf("Git", "Email", "Notifications") })

        for (pipeline in captured) {
            assertEquals(1, pipeline.nodes.filterIsInstance<ConditionNode>().size)
            assertEquals(2, pipeline.nodes.filterIsInstance<JsonataNode>().size)
            val send = pipeline.nodes.filterIsInstance<SendEmailTemplateNode>().single()
            assertEquals("bosca-messages", send.project)
            assertEquals(NotificationTypeKeys.GIT_ACTIVITY, send.notificationType)
            assertEquals(setOf("recipients", "payload"), pipeline.edges.filter { it.target == "send" }.mapNotNull { it.targetPort }.toSet())
        }
    }

    @Test
    fun `payload expressions contain normalized application deep links and typed template contracts`() = runTest {
        val (installer, captured) = installer()
        installer.install(mockk(relaxed = true), mockk(relaxed = true))

        val pullRequest = captured.single { it.acceptedInputType == PullRequestEvent::class.qualifiedName }
        val prPayload = pullRequest.nodes.filterIsInstance<JsonataNode>().single { it.id == "payload" }
        assertTrue("\"https://studio.example\"" in prPayload.expression)
        assertTrue("\"/git/pulls/\"" in prPayload.expression)
        assertEquals("email:bosca-messages/git-pull-request", prPayload.outputType)

        val ref = captured.single { it.acceptedInputType == RefUpdateEvent::class.qualifiedName }
        val refPayload = ref.nodes.filterIsInstance<JsonataNode>().single { it.id == "payload" }
        assertTrue("\"https://studio.example\"" in refPayload.expression)
        assertTrue("\"/git/repositories/\"" in refPayload.expression)
        assertEquals("email:bosca-messages/git-ref-update", refPayload.outputType)
    }

    @Test
    fun `existing pipeline names are preserved`() = runTest {
        val (installer, captured) = installer(listOf(DefaultGitEmailPipelinesInstaller.PULL_REQUEST_PIPELINE))
        installer.install(mockk(relaxed = true), mockk(relaxed = true))

        assertEquals(listOf(DefaultGitEmailPipelinesInstaller.REF_UPDATE_PIPELINE), captured.map { it.name })
    }
}
