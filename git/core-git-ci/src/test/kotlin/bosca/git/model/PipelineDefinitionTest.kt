package bosca.git.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class PipelineDefinitionTest {

    @Test
    fun `PipelineDefinition has sensible defaults`() {
        val definition = PipelineDefinition(name = "Build")
        assertTrue(definition.triggers.isEmpty())
        assertNull(definition.concurrency)
        assertTrue(definition.env.isEmpty())
        assertTrue(definition.secrets.isEmpty())
        assertTrue(definition.jobs.isEmpty())
    }

    @Test
    fun `JobDefinition has sensible defaults`() {
        val job = JobDefinition()
        assertEquals("default", job.runner)
        assertNull(job.timeout)
        assertTrue(job.needs.isEmpty())
        assertNull(job.matrix)
        assertNull(job.condition)
        assertTrue(job.steps.isEmpty())
    }

    @Test
    fun `JobDefinition stores timeout duration`() {
        val job = JobDefinition(timeout = 30.minutes)
        assertEquals(30.minutes, job.timeout)
    }

    @Test
    fun `JobDefinition stores matrix configuration`() {
        val job = JobDefinition(
            matrix = mapOf(
                "java" to listOf("21", "25"),
                "os" to listOf("linux", "macos")
            )
        )
        assertEquals(2, job.matrix!!.size)
        assertEquals(listOf("21", "25"), job.matrix!!["java"])
        assertEquals(listOf("linux", "macos"), job.matrix!!["os"])
    }

    @Test
    fun `StepDefinition with shell command`() {
        val step = StepDefinition(
            name = "Build",
            run = "./gradlew build",
            env = mapOf("CI" to "true")
        )
        assertEquals("Build", step.name)
        assertEquals("./gradlew build", step.run)
        assertNull(step.uses)
        assertNull(step.image)
        assertEquals(mapOf("CI" to "true"), step.env)
    }

    @Test
    fun `StepDefinition with built-in action`() {
        val step = StepDefinition(
            name = "Checkout",
            uses = "checkout",
            with = mapOf("depth" to "1")
        )
        assertEquals("checkout", step.uses)
        assertNull(step.run)
        assertEquals("1", step.with["depth"])
    }

    @Test
    fun `StepDefinition with Docker image`() {
        val step = StepDefinition(
            name = "Docker Build",
            image = "gradle:8.5-jdk21",
            run = "gradle build"
        )
        assertEquals("gradle:8.5-jdk21", step.image)
        assertEquals("gradle build", step.run)
    }

    @Test
    fun `StepDefinition with condition`() {
        val step = StepDefinition(
            name = "Deploy",
            run = "deploy.sh",
            condition = "ref == 'refs/heads/main'"
        )
        assertEquals("ref == 'refs/heads/main'", step.condition)
    }

    @Test
    fun `StepDefinition has sensible defaults`() {
        val step = StepDefinition(name = "Step")
        assertNull(step.uses)
        assertNull(step.run)
        assertNull(step.image)
        assertNull(step.condition)
        assertTrue(step.with.isEmpty())
        assertTrue(step.env.isEmpty())
    }

    @Test
    fun `PipelineDefinition with complete configuration`() {
        val definition = PipelineDefinition(
            name = "Full Pipeline",
            triggers = listOf(
                PipelineTrigger(type = PipelineTriggerType.PUSH, branches = listOf("main")),
                PipelineTrigger(type = PipelineTriggerType.MANUAL)
            ),
            concurrency = PipelineConcurrency(group = "ci-main", cancelInProgress = true),
            env = mapOf("GRADLE_OPTS" to "-Xmx2g"),
            secrets = listOf("DEPLOY_TOKEN"),
            jobs = mapOf(
                "build" to JobDefinition(
                    runner = "linux",
                    steps = listOf(StepDefinition(name = "Build", run = "build"))
                ),
                "deploy" to JobDefinition(
                    runner = "linux",
                    needs = listOf("build"),
                    condition = "branch == 'main'",
                    steps = listOf(StepDefinition(name = "Deploy", run = "deploy"))
                )
            )
        )

        assertEquals("Full Pipeline", definition.name)
        assertEquals(2, definition.triggers.size)
        assertEquals("ci-main", definition.concurrency!!.group)
        assertEquals(1, definition.secrets.size)
        assertEquals(2, definition.jobs.size)
        assertEquals(listOf("build"), definition.jobs["deploy"]!!.needs)
    }
}
