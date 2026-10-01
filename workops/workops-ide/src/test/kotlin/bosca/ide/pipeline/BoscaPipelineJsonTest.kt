package bosca.ide.pipeline

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoscaPipelineJsonTest {
    @Test
    fun `parses a full pipeline tree and preserves its server identity`() {
        val pipeline = BoscaPipelineJson.parsePipeline(
            "server-b",
            "repository-1",
            JsonParser.parseString(PIPELINE).asJsonObject,
        )

        assertEquals("server-b", pipeline.serverProfileId)
        assertEquals("repository-1", pipeline.repositoryId)
        assertEquals("Build", pipeline.name)
        assertEquals("RUNNING", pipeline.runs.single().status)
        assertEquals("compile", pipeline.runs.single().jobs.single().name)
        assertEquals(17, pipeline.runs.single().jobs.single().steps.single().exitCode)
        assertNull(pipeline.runs.single().finished)
        assertEquals("application.zip", pipeline.runs.single().artifacts.single().name)
    }

    @Test
    fun `parses streamed log lines without losing stderr identity`() {
        val line = BoscaPipelineJson.parseLogLine(
            JsonParser.parseString(
                """{"lineNumber":4,"timestamp":"2026-08-09T12:01:02Z","content":"failed","stream":"STDERR"}"""
            ).asJsonObject
        )

        assertEquals(4, line.lineNumber)
        assertEquals("failed", line.content)
        assertEquals("STDERR", line.stream)
    }

    private companion object {
        val PIPELINE = """
            {
              "id":"pipeline-1","name":"Build","filePath":".bosca/build.yaml","triggerTypes":["PUSH"],
              "runs":[{
                "id":"run-1","number":12,"status":"RUNNING","ref":"main","commitSha":"abc123",
                "triggerType":"MANUAL","created":"2026-08-09T12:00:00Z","started":"2026-08-09T12:00:01Z",
                "finished":null,"durationSeconds":null,
                "artifacts":[{"id":"artifact-1","name":"application.zip","sizeBytes":42,"created":"2026-08-09T12:01:00Z"}],
                "jobs":[{
                  "id":"job-1","name":"compile","status":"RUNNING","runnerLabel":"linux","attempt":1,
                  "awaitingRequirements":false,"awaitingApproval":false,"approvalRequired":false,
                  "errorMessage":null,"started":"2026-08-09T12:00:01Z","finished":null,
                  "steps":[{"id":"step-1","name":"gradle","ordinal":1,"status":"FAILURE","exitCode":17,
                    "errorMessage":"compiler failed","started":"2026-08-09T12:00:02Z","finished":"2026-08-09T12:01:00Z"}]
                }]
              }]
            }
        """.trimIndent()
    }
}
