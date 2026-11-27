package bosca.workops.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkOpsProjectStateContractTest {
    @Test fun `dependency state retains separate consumer pages constraints and inspected deletion versions`() {
        val dependency = """{"id":"dependency","consumerId":"project","providerId":"provider","constraint":"^2.0","type":"RUNTIME","status":"SATISFIED","version":7}"""
        val model = assertWorkOpsSerialization(WorkOpsProjectDependenciesModel.serializer(), """{"projectId":"project"}""", """{
          "projectId":"project","offset":25,"consumersOffset":50,"targetId":"dependency","dependencies":[$dependency],"consumers":[$dependency],
          "projects":[{"id":"provider","key":"LIB","name":"Library"}],"loaded":true,"creating":true,"providerDraft":"provider",
          "typeDraft":"RUNTIME","constraintDraft":"^2.0","deleteTarget":$dependency,"resultId":"created","feedback":{"message":"Stale dependency","failed":true}
        }""")
        assertEquals(50L, model.consumersOffset)
        assertEquals(7L, model.deleteTarget?.version)
        assertEquals("^2.0", model.constraintDraft)
    }

    @Test fun `repository state preserves unavailable identities human Git author and generated config bytes`() {
        val repository = """{"id":"attachment","repositoryId":"repository","name":"Source","slug":"source","archived":true,"available":false}"""
        val model = assertWorkOpsSerialization(WorkOpsProjectRepositoriesModel.serializer(), """{"projectId":"project"}""", """{
          "projectId":"project","offset":25,"targetId":"attachment","rows":[$repository],"choices":[{"value":"repository","label":"Source","disabled":true}],
          "loaded":true,"repositoryDraft":"repository","authorNameDraft":"Human Author","authorEmailDraft":"human@example.test","authorInitialized":true,
          "deleteTarget":$repository,"setupTarget":$repository,"generated":{"repositoryId":"repository","repositoryName":"Source","path":"deployment.yaml",
            "branch":"main","sha":"saved-sha","content":"apiVersion: v1\nkind: ConfigMap\n"},
          "resultId":"created","feedback":{"message":"Setup rejected","failed":true}
        }""")
        assertFalse(model.rows.single().available)
        assertEquals("Human Author", model.authorNameDraft)
        assertEquals("apiVersion: v1\nkind: ConfigMap\n", model.generated?.content)
    }

    @Test fun `telemetry association state keeps application and service pagination independent`() {
        val model = assertWorkOpsSerialization(WorkOpsProjectTelemetryModel.serializer(),
            """{"projectId":"project","kind":"applications"}""", """{
          "projectId":"project","kind":"services","offset":25,"targetId":"association","otherOffset":50,
          "rows":[{"id":"association","value":"service.example"}],"loaded":true,"draft":"service.example",
          "deleteTarget":{"id":"association","value":"service.example"},"resultId":"created","feedback":{"message":"Delete rejected","failed":true}
        }""")
        assertEquals(50L, model.otherOffset)
        assertEquals("association", model.deleteTarget?.id)
        assertEquals("serviceOffset", model.offsetKey)
        assertEquals("applicationOffset", WorkOpsProjectTelemetryModel("project", "applications").offsetKey)
    }
}
