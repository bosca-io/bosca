package bosca.cli.ci

import bosca.cli.api.NetworkClient
import bosca.graphql.gen.GitPipelineTriggerType
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class CiApiPipelineRunTest {

    @Test
    fun `promotion pipeline run decodes through the generated client`() = runTest {
        MockWebServer().use { server ->
            val runId = Uuid.random()
            val pipelineId = Uuid.random()
            val repositoryId = Uuid.random()
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(
                        """
                        {
                          "data": {
                            "git": {
                              "pipelineRun": {
                                "id": "$runId",
                                "pipelineId": "$pipelineId",
                                "repositoryId": "$repositoryId",
                                "commitSha": "abcdef123456",
                                "ref": "refs/tags/6.6.9",
                                "triggerType": "PROMOTION",
                                "triggeredBy": null,
                                "status": "RUNNING",
                                "number": 42,
                                "concurrencyGroup": null,
                                "created": "2026-08-04T07:39:41Z",
                                "started": "2026-08-04T07:52:25Z",
                                "finished": null,
                                "durationSeconds": null,
                                "jobs": []
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    )
                    .build(),
            )
            server.start()
            val api = CiApi(NetworkClient(server.url("/graphql").toString()))

            val run = requireNotNull(api.getPipelineRun(runId))

            assertEquals(runId, run.id)
            assertEquals(GitPipelineTriggerType.PROMOTION, run.triggerType)
        }
    }
}
