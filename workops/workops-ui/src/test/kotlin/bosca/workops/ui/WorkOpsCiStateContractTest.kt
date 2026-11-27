package bosca.workops.ui

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkOpsCiStateContractTest {
    private val inputs = """{"inputs":[{"name":"deploy.enabled","type":"boolean","defaultValue":"true",
      "description":"Deploy toggle","options":[],"required":true}]}"""

    @Test fun `CI launch drafts keep original declarations separate from submitted false values`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseCiLaunchDraft.serializer(), """{"trigger":"RELEASE"}""", """{
          "trigger":"PROMOTION","environment":"production","inputs":$inputs,"values":{"input0":false},"allowDowngrade":true
        }""")
        assertEquals(JsonPrimitive(true), model.inputs.defaults["input0"])
        assertEquals(JsonPrimitive(false), model.values["input0"])
        assertEquals(JsonPrimitive("false"), model.inputs.payload(model.values.toString())["deploy.enabled"])
    }

    @Test fun `CI actions retain exact confirmation identities comments patch data and rollback revision`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseCiModel.serializer(), """{"id":"release"}""", """{
          "id":"release","read":{"id":"release","selectedRunId":"run"},
          "launching":{"trigger":"PROMOTION","environment":"production","inputs":$inputs,"values":{"input0":false},"allowDowngrade":true},
          "confirming":{"kind":"rollbackEnvironment","runId":"run","jobId":"job","environment":"production","label":"Production"},
          "patching":true,"patchValues":{"version":"2.0"},"comments":{"job":"Human approval note"},
          "rollbackRevisionDraft":"7","rollbackOutcomes":["Application rolled back"],"nextHref":"/workops/releases/next",
          "draftRevision":3,"revision":5,"feedback":{"message":"Action rejected","failed":true}
        }""")
        assertEquals("7", model.rollbackRevisionDraft)
        assertEquals("job", model.confirming?.jobId)
        assertEquals("Human approval note", model.comments["job"])
        assertEquals(5L, model.revision)
    }

    @Test fun `CI read state retains independent permissions plans native runs and failed run details`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseCiReadModel.serializer(), """{"id":"release"}""", """{
          "id":"release","release":{"id":"release","programId":"program","name":"Release","version":7},
          "components":[{"projectId":"project","versionId":"version"}],"componentsLoaded":true,
          "plans":[{"pipelineId":"pipeline","pipelineName":"Release","repositoryId":"repo","repositorySlug":"source","triggerType":"RELEASE",
            "environment":null,"promotesFrom":null,"inputs":[{"name":"deploy.enabled","type":"boolean","defaultValue":"true","description":"Toggle","options":[],"required":true}],
            "jobs":[{"key":"build","environment":"staging","approvalRequired":true,"needs":["prepare"],"requirements":["artifact raw:builds:input:1"],"depth":1,
              "steps":[{"name":"Build","action":"run"}]}]}],"plansLoaded":true,
          "summaries":[{"runId":"run","status":"RUNNING","startedAt":"2026-11-17T10:00:00Z","finishedAt":null,"durationMs":null}],"summariesLoaded":true,
          "runs":[{"id":"run","pipelineId":"pipeline","repositoryId":"repo","ref":"refs/heads/main","triggerType":"RELEASE","status":"RUNNING","number":3,
            "parameters":{"deploy.enabled":"false"},"created":"2026-11-17T10:00:00Z","started":null,"finished":null,"durationSeconds":null,"jobs":[]}],
          "runErrors":{"unavailable-run":"Read denied"},"access":{"canManage":true,"repositories":[{"repositoryId":"repo","canEdit":true,"canExecute":true}],
            "environments":[{"key":"production","canExecute":false}]},"accessLoaded":true,"readiness":["Unresolved dependency"],"readinessLoaded":true,
          "selectedRunId":"run","feedback":{"message":"Refresh failed","failed":true},"componentFeedback":{"message":"Bundle failed","failed":true},
          "plansFeedback":{"message":"Plans failed","failed":true},"runsFeedback":{"message":"Runs failed","failed":true},
          "accessFeedback":{"message":"Access failed","failed":true},"readinessFeedback":{"message":"Readiness failed","failed":true}
        }""")
        assertTrue(model.canExecute("repo"))
        assertEquals("Read denied", model.runErrors["unavailable-run"])
        assertEquals("run", model.selectedRun?.id)
        assertEquals("Resolve the release dependency blockers first.", model.startReason)
    }
}
