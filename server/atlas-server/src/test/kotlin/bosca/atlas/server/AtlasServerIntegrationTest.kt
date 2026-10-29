package bosca.atlas.server

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests the production composition with real Git HTTP pushes and durable queue execution. */
class AtlasServerIntegrationTest {
    @Test
    fun `combined Atlas serves Git and BML and consumes Git CI jobs`() = verify(false)

    @Test
    fun `API without workers retains jobs for a separate Atlas runner`() = verify(true)

    private fun verify(separateRunner: Boolean) {
        AtlasProcessFixture().use { fixture ->
            val standaloneUi = if (separateRunner) fixture.newOrigin() else null
            val registry = if (separateRunner) fixture.start("registry", mapOf(
                "ATLAS_API_ENABLED" to "false", "ATLAS_GIT_ENABLED" to "false",
                "ATLAS_UI_ENABLED" to "false", "ATLAS_RUNNER_ENABLED" to "false",
            )).first else null
            val (api, embeddedUi) = fixture.start("server", if (separateRunner) mapOf(
                "ATLAS_RUNNER_ENABLED" to "false", "ATLAS_UI_ENABLED" to "false",
                "ATLAS_ARTIFACTS_ENABLED" to "false",
                "GIT_UI_URL" to requireNotNull(standaloneUi), "APP_URL" to standaloneUi,
                "APP_ALLOWED_ORIGIN" to standaloneUi, "OAUTH2_ALLOWED_REDIRECT" to standaloneUi,
            ) else emptyMap())
            val ui = if (separateRunner) fixture.startUi(api, requireNotNull(standaloneUi)) else embeddedUi
            val admin = fixture.graphql(ui, """mutation { security { login { password(identifier: "admin", password: "atlas-admin-password") { token { token } } } } }""")
                .at("security", "login", "password", "token", "token").jsonPrimitive.content
            val artifactOrigin = registry ?: api
            assertEquals(401, fixture.request(artifactOrigin, "/v2/").statusCode())
            assertEquals(200, fixture.request(artifactOrigin, "/v2/", token = admin).statusCode())
            assertEquals(200, fixture.request(artifactOrigin, "/npm/-/whoami", token = admin).statusCode())
            if (registry != null) {
                assertEquals(404, fixture.request(registry, "/graphql").statusCode())
                assertEquals(404, fixture.request(api, "/v2/", token = admin).statusCode())
            }
            val namespace = fixture.graphql(ui, """mutation { artifactsAdmin { createNamespace(name: "atlas-builds", public: false) { id } } }""", admin)
                .at("artifactsAdmin", "createNamespace", "id").jsonPrimitive.content
            fixture.graphql(api, """mutation { security { signup { passwordV2(identifier: "builder@example.test", password: "atlas-builder-password", profile: {name: "Atlas Builder", visibility: PUBLIC, attributes: []}) { principal { id } } } } }""")
            val login = fixture.graphql(api, """mutation { security { login { password(identifier: "builder@example.test", password: "atlas-builder-password") { principal { id } token { token } } } } }""")
            val user = login.at("security", "login", "password", "principal", "id").jsonPrimitive.content
            val token = login.at("security", "login", "password", "token", "token").jsonPrimitive.content
            val identity = fixture.graphql(api, "{ profiles { current { id } } security { principals { current { groups { id type } } } } }", token)
            val owner = identity.at("profiles", "current").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
            val group = identity.at("security", "principals", "current", "groups").jsonArray
                .first { it.jsonObject.getValue("type").jsonPrimitive.content == "PRINCIPAL" }.jsonObject.getValue("id").jsonPrimitive.content
            val repository = fixture.graphql(api, """mutation { git { createRepository(input: {name: "Atlas CI", slug: "atlas-ci", ownerId: "$owner", initializeWithReadme: true}) { id cloneUrl } } }""", admin)
                .at("git", "createRepository")
            val repositoryId = repository.jsonObject.getValue("id").jsonPrimitive.content
            val cloneUrl = repository.jsonObject.getValue("cloneUrl").jsonPrimitive.content
            for (action in listOf("VIEW", "EDIT", "EXECUTE")) {
                fixture.graphql(api, """mutation { git { addPermission(permission: {entityId: "$repositoryId", groupId: "$group", action: $action}) } }""", admin)
            }
            assertEquals(200, fixture.request(ui, "/login").statusCode())
            assertTrue("Bosca Atlas" in fixture.request(ui, "/login").body())
            assertEquals(200, fixture.request(ui, "/repositories/$repositoryId", token = token).statusCode())
            assertEquals(404, fixture.request(api, "/login").statusCode())
            assertEquals(401, fixture.request(api, cloneUrl.removePrefix(api) + "/info/refs?service=git-upload-pack").statusCode())
            val rawPath = cloneUrl.removePrefix(api).removeSuffix(".git") + "/raw/main/README.md"
            val raw = fixture.request(ui, rawPath, token = token)
            assertEquals(200, raw.statusCode(), raw.body())

            val credentials = UsernamePasswordCredentialsProvider("builder@example.test", "atlas-builder-password")
            val checkout = File(fixture.directory, "checkout")
            val commit = Git.cloneRepository().setURI(cloneUrl).setDirectory(checkout).setCredentialsProvider(credentials).call().use { git ->
                val pipeline = File(checkout, ".bosca/pipelines/atlas.yaml")
                pipeline.parentFile.mkdirs()
                pipeline.writeText("""
                    name: Atlas Check
                    on:
                      push:
                        branches: ["**"]
                    jobs:
                      check:
                        runner: atlas-test
                        requires:
                          - type: raw
                            namespace: atlas-builds
                            coordinate: input:1.0
                        artifacts:
                          - type: raw
                            namespace: atlas-builds
                            coordinate: output:1.0
                        steps:
                          - name: Check
                            run: echo atlas
                """.trimIndent())
                git.add().addFilepattern(".").call()
                val sha = git.commit().setAuthor("Atlas Builder", "builder@example.test").setMessage("Add Atlas CI").call().name
                val results = git.push().setCredentialsProvider(credentials).call().flatMap { it.remoteUpdates }
                assertTrue(results.isNotEmpty())
                assertTrue(results.all { it.status == RemoteRefUpdate.Status.OK }, results.toString())
                sha
            }
            fun runs() = fixture.graphql(api, """{ git { pipelineRuns(repositoryId: "$repositoryId") { id commitSha triggeredBy jobs { id status } } } }""", token)
                .at("git", "pipelineRuns").jsonArray
            if (separateRunner) {
                Thread.sleep(1_000)
                assertTrue(runs().isEmpty(), "API-only process must enqueue without consuming CI jobs")
                fixture.start("pipelines-worker", mapOf(
                    "ATLAS_API_ENABLED" to "false", "ATLAS_GIT_ENABLED" to "false", "ATLAS_ARTIFACTS_ENABLED" to "false",
                    "ATLAS_UI_ENABLED" to "false", "ATLAS_RUNNER_ENABLED" to "true",
                    "ATLAS_RUNNER_QUEUES" to "pipelines,common", "ATLAS_RUNNER_LISTENERS_ENABLED" to "false",
                    "ATLAS_SCHEDULER_ENABLED" to "false",
                ))
                Thread.sleep(1_000)
                assertTrue(runs().isEmpty(), "A pipelines-only worker must leave Git jobs queued")
                val (worker, _) = fixture.start("worker", mapOf(
                    "ATLAS_API_ENABLED" to "false", "ATLAS_GIT_ENABLED" to "false", "ATLAS_ARTIFACTS_ENABLED" to "false",
                    "ATLAS_UI_ENABLED" to "false", "ATLAS_RUNNER_ENABLED" to "true",
                    "ATLAS_RUNNER_QUEUES" to "git",
                ))
                assertEquals(404, fixture.request(worker, "/graphql").statusCode())
                assertEquals(404, fixture.request(worker, "/login").statusCode())
            }
            fixture.await("Git trigger consumed") { runs().any { it.jsonObject.getValue("commitSha").jsonPrimitive.content == commit } }
            val run = runs().first { it.jsonObject.getValue("commitSha").jsonPrimitive.content == commit }.jsonObject
            assertEquals(user, run.getValue("triggeredBy").jsonPrimitive.content)
            assertEquals(1, run.getValue("jobs").jsonArray.size)
            assertFalse(fixture.logs().contains("no artifact verifier is available"), fixture.logs())
            val agent = fixture.graphql(api, """mutation { git { registerAgent(name: "Atlas test", labels: ["atlas-test"], mode: RUNNER) { agent { id } token } } }""", admin)
                .at("git", "registerAgent")
            val agentId = agent.at("agent", "id").jsonPrimitive.content
            val agentToken = agent.jsonObject.getValue("token").jsonPrimitive.content
            fun claim() = fixture.graphql(api, """mutation { git { claimJob(agentId: "$agentId", labels: ["atlas-test"]) { id steps { id } } } }""", agentToken)
                .at("git", "claimJob")
            assertEquals(JsonNull, claim(), "Missing input artifacts must gate the job")
            val input = fixture.upload(artifactOrigin, "/raw/atlas-builds/api/input/1.0/input.txt", "input bytes", admin)
            assertEquals(201, input.statusCode(), input.body())
            var claimed: String? = null
            var stepId: String? = null
            fixture.await("artifact requirement released") {
                val result = claim()
                if (result == JsonNull) false else {
                    claimed = result.jsonObject.getValue("id").jsonPrimitive.content
                    stepId = result.at("steps").jsonArray.first().at("id").jsonPrimitive.content
                    true
                }
            }
            val runId = run.getValue("id").jsonPrimitive.content
            AtlasLogSubscription(ui, token, "subscription { pipelineStepLogs(repositoryId: \"$repositoryId\", stepId: \"$stepId\") { content lineNumber } }").use { logs ->
                var message: JsonObject? = null
                for (line in 1..40) {
                    fixture.graphql(api, """mutation { git { appendPipelineLogs(repositoryId: "$repositoryId", runId: "$runId", jobId: "$claimed", stepId: "$stepId", lines: [{lineNumber: $line, timestamp: "${java.time.OffsetDateTime.now()}", content: "atlas-log-$line", stream: "STDOUT"}]) } }""", agentToken)
                    message = logs.poll()
                    if (message != null) break
                }
                val received = requireNotNull(message) { "No Atlas live log arrived through the UI proxy" }
                assertEquals("next", received.getValue("type").jsonPrimitive.content, received.toString())
                assertTrue(received.at("payload", "data", "pipelineStepLogs", "content").jsonPrimitive.content.startsWith("atlas-log-"))
            }
            val output = fixture.upload(artifactOrigin, "/raw/atlas-builds/api/output/1.0/output.txt", "output bytes", admin)
            assertEquals(201, output.statusCode(), output.body())
            fixture.graphql(api, """mutation { git { updateJobStatus(jobId: "$claimed", status: SUCCESS) { status } } }""", agentToken)
            fixture.await("CI run finalized") {
                fixture.graphql(api, """{ git { pipelineRun(id: "${run.getValue("id").jsonPrimitive.content}") { status } } }""", token)
                    .at("git", "pipelineRun", "status").jsonPrimitive.content == "SUCCESS"
            }
            val repositories = fixture.graphql(api, """{ artifactsAdmin { repositories(namespaceId: "$namespace") { name versions { completed completedCommitSha } } } }""", admin)
                .at("artifactsAdmin", "repositories").jsonArray
            val produced = repositories.first { it.jsonObject.getValue("name").jsonPrimitive.content == "output" }
                .at("versions").jsonArray.single()
            assertTrue(produced.at("completed").jsonPrimitive.boolean)
            assertEquals(commit, produced.at("completedCommitSha").jsonPrimitive.content)
            fixture.await("scheduler execution recorded") {
                fixture.graphql(api, "{ scheduler { history(source: SCHEDULER, status: COMPLETED) { id } } }", admin)
                    .at("scheduler", "history").jsonArray.isNotEmpty()
            }
            assertEquals(200, fixture.request(ui, "/_bml/app.js").statusCode())
        }
    }

    private fun JsonElement.at(vararg fields: String): JsonElement = fields.fold(this) { value, field -> value.jsonObject.getValue(field) }
}
