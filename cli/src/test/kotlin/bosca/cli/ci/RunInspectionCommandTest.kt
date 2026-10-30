package bosca.cli.ci

import bosca.cli.config.CliConfigStore
import bosca.cli.config.CliInvocation
import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RunInspectionCommandTest {
    private lateinit var tempDir: File
    private val repoId = Uuid.random()
    private val pipelineId = Uuid.random()
    private val runId = Uuid.random()
    private val jobId = Uuid.random()
    private val secondJobId = Uuid.random()
    private val stepId = Uuid.random()
    private val secondStepId = Uuid.random()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("run-inspection-test").toFile()
        CliConfigStore.directoryOverride = File(tempDir, "config")
        CliConfigStore.workingDirectoryOverride = tempDir
        CliInvocation.selectProfile(null)
    }

    @AfterTest
    fun tearDown() {
        CliInvocation.selectProfile(null)
        CliConfigStore.workingDirectoryOverride = null
        CliConfigStore.directoryOverride = null
        tempDir.deleteRecursively()
    }

    @Test
    fun `pipeline listing exposes IDs names and paths`() {
        MockWebServer().use { server ->
            server.enqueue(response("pipelines", """[{
                "id":"$pipelineId","repositoryId":"$repoId","name":"Release",
                "filePath":".bosca/pipelines/release.yaml","created":"2026-10-01T00:00:00Z","updated":"2026-10-01T00:00:00Z"
            }]"""))
            server.start()
            val result = PipelineListCommand().test("${auth(server)} --repo-id $repoId")
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue("$pipelineId" in result.stdout)
            assertTrue("Release" in result.stdout)
            assertTrue(".bosca/pipelines/release.yaml" in result.stdout)
        }
    }

    @Test
    fun `repository run listing exposes IDs and sends pagination`() {
        MockWebServer().use { server ->
            server.enqueue(response("pipelineRuns", "[${runJson()}]"))
            server.start()
            val result = RunListCommand().test("${auth(server)} --repo-id $repoId --offset 40 --limit 10")
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue("$runId" in result.stdout)
            assertTrue("$pipelineId" in result.stdout)
            assertTrue("refs/heads/main" in result.stdout)
            val variables = variables(server)
            assertEquals("40", variables.getValue("offset").jsonPrimitive.content)
            assertEquals("10", variables.getValue("limit").jsonPrimitive.content)
        }
    }

    @Test
    fun `pipeline run listing filters on the server before pagination`() {
        MockWebServer().use { server ->
            server.enqueue(response("pipelines", """[{"id":"$pipelineId","runs":[${runJson()}]}]"""))
            server.start()
            val result = RunListCommand().test(
                "${auth(server)} --repo-id $repoId --pipeline-id $pipelineId --offset 20 --limit 5",
            )
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue("$runId" in result.stdout)
            val variables = variables(server)
            assertEquals("$pipelineId", variables.getValue("pipelineId").jsonPrimitive.content)
            assertEquals("$repoId", variables.getValue("repositoryId").jsonPrimitive.content)
            assertEquals("20", variables.getValue("offset").jsonPrimitive.content)
        }
    }

    @Test
    fun `run details and job and step lists expose usable identifiers`() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(response("pipelineRun", runJson())) }
            server.start()
            val details = RunGetCommand().test("${auth(server)} --id $runId")
            val jobs = RunJobsCommand().test("${auth(server)} --run-id $runId")
            val steps = RunStepsCommand().test("${auth(server)} --run-id $runId --job-id $jobId")
            for (result in listOf(details, jobs, steps)) assertEquals(0, result.statusCode, result.stderr)
            for (id in listOf(runId, jobId, stepId)) assertTrue("$id" in details.stdout)
            assertTrue("build" in jobs.stdout)
            assertTrue("FAILURE" in steps.stdout)
            assertTrue("$stepId" in steps.stdout)
            assertTrue("$secondStepId" !in steps.stdout)
        }
    }

    @Test
    fun `step logs drain every page and recognize uppercase stderr`() {
        MockWebServer().use { server ->
            server.enqueue(response("pipelineLogs", logs(1..200)))
            server.enqueue(response("pipelineLogs", logs(201..201, "STDERR")))
            server.enqueue(response("pipelineLogs", "[]"))
            server.start()
            val result = RunLogsCommand().test("${auth(server)} --step-id $stepId")
            assertEquals(0, result.statusCode, result.stderr)
            assertEquals(201, result.stdout.lineSequence().count { "line-" in it })
            assertTrue("[ERR] line-201" in result.stdout)
            assertEquals(listOf("0", "200", "201"), List(3) {
                variables(server).getValue("offset").jsonPrimitive.content
            })
        }
    }

    @Test
    fun `run logs include all steps with independent offsets and labels`() {
        MockWebServer().use { server ->
            server.enqueue(response("pipelineRun", runJson()))
            for (number in listOf(1, 2)) {
                server.enqueue(response("pipelineLogs", logs(number..number)))
                server.enqueue(response("pipelineLogs", "[]"))
            }
            server.start()
            val result = RunLogsCommand().test("${auth(server)} --run-id $runId --offset 3")
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue("[build/compile] line-1" in result.stdout)
            assertTrue("[test/verify] line-2" in result.stdout)
            server.takeRequest()
            assertEquals(listOf("3", "4", "3", "4"), List(4) {
                variables(server).getValue("offset").jsonPrimitive.content
            })
        }
    }

    @Test
    fun `job logs exclude other jobs`() {
        MockWebServer().use { server ->
            server.enqueue(response("pipelineRun", runJson()))
            server.enqueue(response("pipelineLogs", logs(1..1)))
            server.enqueue(response("pipelineLogs", "[]"))
            server.start()
            val result = RunLogsCommand().test("${auth(server)} --run-id $runId --job-id $jobId")
            assertEquals(0, result.statusCode, result.stderr)
            assertTrue("[build/compile]" in result.stdout)
            assertTrue("[test/verify]" !in result.stdout)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `follow keeps offsets and stops when the run completes`() {
        MockWebServer().use { server ->
            server.enqueue(response("pipelineRun", runJson("RUNNING", twoJobs = false)))
            server.enqueue(response("pipelineLogs", logs(1..1)))
            server.enqueue(response("pipelineLogs", "[]"))
            server.enqueue(response("pipelineRun", runJson(twoJobs = false)))
            server.enqueue(response("pipelineLogs", logs(2..2)))
            server.enqueue(response("pipelineLogs", "[]"))
            server.start()
            val result = RunLogsCommand().test("${auth(server)} --run-id $runId --follow")
            assertEquals(0, result.statusCode, result.stderr)
            assertEquals(1, result.stdout.lineSequence().count { "line-1" in it })
            assertEquals(1, result.stdout.lineSequence().count { "line-2" in it })
            assertEquals(6, server.requestCount)
            server.takeRequest()
            assertEquals("0", variables(server).getValue("offset").jsonPrimitive.content)
            assertEquals("1", variables(server).getValue("offset").jsonPrimitive.content)
            server.takeRequest()
            assertEquals("1", variables(server).getValue("offset").jsonPrimitive.content)
        }
    }

    @Test
    fun `follow stops at the selected job or step while the run is active`() {
        MockWebServer().use { server ->
            server.start()
            for (selector in listOf("--job-id $jobId", "--step-id $stepId")) {
                server.enqueue(response("pipelineRun", runJson().replace("\"status\":\"FAILURE\",\"number\"", "\"status\":\"RUNNING\",\"number\"")))
                server.enqueue(response("pipelineLogs", "[]"))
                val result = RunLogsCommand().test("${auth(server)} --run-id $runId $selector --follow")
                assertEquals(0, result.statusCode, result.stderr)
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `empty listings report no results`() {
        MockWebServer().use { server ->
            server.enqueue(response("pipelines", "[]"))
            server.enqueue(response("pipelineRuns", "[]"))
            server.enqueue(response("pipelines", """[{"id":"$pipelineId","runs":[]}]"""))
            server.start()
            val pipelines = PipelineListCommand().test("${auth(server)} --repo-id $repoId")
            val runs = RunListCommand().test("${auth(server)} --repo-id $repoId")
            val filtered = RunListCommand().test("${auth(server)} --repo-id $repoId --pipeline-id $pipelineId")
            for (result in listOf(pipelines, runs, filtered)) {
                assertEquals(0, result.statusCode, result.stderr)
                assertTrue("No pipeline" in result.stdout)
            }
        }
    }

    @Test
    fun `invalid log selectors and pagination fail without network requests`() {
        MockWebServer().use { server ->
            server.start()
            for (args in listOf(
                "", "--step-id $stepId --follow", "--job-id $jobId",
                "--run-id $runId --job-id $jobId --step-id $stepId", "--step-id $stepId --offset -1",
            )) {
                val result = RunLogsCommand().test("${auth(server)} $args")
                assertTrue(result.statusCode != 0, args)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `missing runs pipelines jobs and steps produce errors`() {
        MockWebServer().use { server ->
            server.enqueue(response("pipelineRun", "null"))
            server.enqueue(response("pipelines", "[]"))
            repeat(2) { server.enqueue(response("pipelineRun", runJson())) }
            server.start()
            val unknown = Uuid.random()
            val results = listOf(
                RunGetCommand().test("${auth(server)} --id $runId"),
                RunListCommand().test("${auth(server)} --repo-id $repoId --pipeline-id $pipelineId"),
                RunStepsCommand().test("${auth(server)} --run-id $runId --job-id $unknown"),
                RunLogsCommand().test("${auth(server)} --run-id $runId --step-id $unknown"),
            )
            for (result in results) {
                assertTrue(result.statusCode != 0)
                assertTrue("not found" in result.stderr, result.stderr)
            }
            assertEquals(4, server.requestCount)
        }
    }

    private fun auth(server: MockWebServer) = "--url ${server.url("/graphql")} --token test-token"

    private fun response(field: String, value: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body("""{"data":{"git":{"$field":$value}}}""").build()

    private fun variables(server: MockWebServer) =
        Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
            .getValue("variables").jsonObject

    private fun logs(numbers: IntRange, stream: String = "STDOUT") = numbers.joinToString(",", "[", "]") {
        """{"lineNumber":$it,"timestamp":"2026-10-01T00:00:00Z","content":"line-$it","stream":"$stream"}"""
    }

    private fun runJson(status: String = "FAILURE", twoJobs: Boolean = true): String {
        fun job(id: Uuid, step: Uuid, name: String, stepName: String) = """{
            "id":"$id","pipelineRunId":"$runId","name":"$name","status":"$status","runnerLabel":"linux",
            "agentId":null,"matrixValues":null,"dependsOn":[],"started":null,"finished":null,
            "steps":[{"id":"$step","pipelineJobId":"$id","name":"$stepName","ordinal":1,"status":"$status",
            "exitCode":1,"started":null,"finished":null}]
        }"""
        val jobs = listOfNotNull(
            job(jobId, stepId, "build", "compile"),
            if (twoJobs) job(secondJobId, secondStepId, "test", "verify") else null,
        ).joinToString(",")
        return """{
            "id":"$runId","pipelineId":"$pipelineId","repositoryId":"$repoId","commitSha":"abcdef123456",
            "ref":"refs/heads/main","triggerType":"MANUAL","triggeredBy":null,"status":"$status","number":42,
            "concurrencyGroup":null,"created":"2026-10-01T00:00:00Z","started":null,"finished":null,
            "durationSeconds":null,"jobs":[$jobs]
        }"""
    }
}
