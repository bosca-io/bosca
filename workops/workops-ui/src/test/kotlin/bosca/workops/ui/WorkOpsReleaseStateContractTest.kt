package bosca.workops.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkOpsReleaseStateContractTest {
    private val release = """{"id":"release","programId":"program","name":"Release","description":"Notes","planned":"2026-12-01",
      "released":"2026-12-02","version":7,"latestRunId":"run","latestStatus":"SUCCESS","statusLoaded":true,"statusError":"Retained error"}"""
    private val component = """{"projectId":"project","versionId":"version","order":2,"status":"DEPLOYED","deployed":"2026-12-02",
      "rollbackVersionId":"previous","projectKey":"APP","projectName":"Application","versionName":"1.0"}"""
    private val version = """{"id":"version","name":"1.0","description":"Notes","version":7,"released":true,"archived":true,
      "date":"2026-12-01","startDate":"2026-11-01","sequenceNumber":2}"""
    private val picker = """{"programId":"program","projects":[{"id":"project","key":"APP","name":"Application"}],
      "pickProjectId":"project","pickVersionId":"version","versions":[$version],
      "createdVersions":[{"projectId":"project","item":$version}],"excludedVersionIds":["bundled"],"choicesLoaded":true,
      "versionsLoaded":true,"creatingVersion":true,"versionDraft":{"name":"Authored version","description":"Notes","startDate":"2026-11-01","releaseDate":"2026-12-01"},
      "feedback":{"message":"Picker unavailable","failed":true},"versionFeedback":{"message":"Create rejected","failed":true}}"""
    private val artifact = """{"projectId":"project","type":"oci","namespace":"images","coordinate":"application:1.0",
      "environments":["production"],"versionId":"version","publicationId":"publication","status":"PUBLISHED","stale":true}"""
    private val note = """{"id":"note","versionId":"version","sourceLocale":"en","generated":"2026-11-17","manuallyEdited":true,
      "variants":{"en":{"playReleaseNotes":"Authored notes"}},
      "localized":[{"locale":"en","playReleaseNotes":"Play notes","appStoreWhatsNew":"Store notes","testFlightWhatToTest":"Test notes"}],
      "variantError":"One unavailable translation"}"""

    @Test fun `release lists preserve per-release native run status separately from program selection`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseListModel.serializer(), "{}", """{
          "programId":"program","offset":25,"programs":[{"id":"program","name":"Program"}],"rows":[$release],
          "loaded":true,"feedback":{"message":"One status unavailable","failed":true}
        }""")
        assertEquals("run", model.rows.single().latestRunId)
        assertEquals("Released", model.rows.single().statusLabel)
    }

    @Test fun `release builder keeps staged bundle outcomes and the independent version creation draft`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseBuildModel.serializer(), """{"programId":"program"}""", """{
          "programId":"program","programName":"Program","active":true,
          "draft":{"name":"Authored release","description":"Notes","date":"2026-12-01","step":2,
            "components":[{"projectId":"project","projectKey":"APP","projectName":"Application","versionId":"version","versionName":"1.0","bundled":true,"error":"Retry remaining bundle"}]},
          "picker":$picker,"createdId":"created","feedback":{"message":"Partial bundle","failed":true}
        }""")
        assertTrue(model.draft.components.single().bundled)
        assertEquals("created", model.createdId)
        assertEquals("2026-11-01", model.picker.versionDraft.startDate)
    }

    @Test fun `release edits retain inspected removals deletions and release dates across state encoding`() {
        assertEquals("/workops/releases", WorkOpsReleaseEditModel("release").listHref)
        val model = assertWorkOpsSerialization(WorkOpsReleaseEditModel.serializer(), """{"id":"release"}""", """{
          "id":"release","release":$release,"canManage":true,"editing":$release,"nameDraft":"Authored release","descriptionDraft":"Notes",
          "dateDraft":"2026-12-01","picker":$picker,"components":[$component],"componentsLoaded":true,"removing":$component,"deleting":$release,
          "nextHref":"/workops/releases?program=program","revision":3,"feedback":{"message":"Stale release","failed":true},
          "readFeedback":{"message":"Read unavailable","failed":true}
        }""")
        assertEquals(7L, model.editing?.version)
        assertEquals("previous", model.removing?.rollbackVersionId)
        assertEquals("version", model.picker.pickVersionId)
        assertEquals("/workops/releases?programId=program", model.listHref)
    }

    @Test fun `release detail keeps independent read outcomes and nested delivery workspaces`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseDetailModel.serializer(), """{"id":"release"}""", """{
          "id":"release","release":$release,"programName":"Program","components":[$component],"componentsLoaded":true,
          "readiness":["Unresolved dependency"],"readinessLoaded":true,"canManage":true,"accessLoaded":true,
          "feedback":{"message":"Read failed","failed":true},"componentFeedback":{"message":"Bundle unavailable","failed":true},
          "labelFeedback":{"message":"Labels unavailable","failed":true},"readinessFeedback":{"message":"Readiness unavailable","failed":true},
          "accessFeedback":{"message":"Permissions unavailable","failed":true},"ci":{"id":"release"},"artifacts":{"id":"release"},
          "notes":{"id":"release"},"revision":3,"ciContextRevision":5,"environments":{"id":"release","programId":"program"}
        }""")
        assertEquals(5L, model.ciContextRevision)
        assertEquals("release", model.ci?.id)
        assertEquals("program", model.environments?.programId)
    }

    @Test fun `artifact state retains stale publications and failed run identities without losing declarations`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseArtifactsModel.serializer(), """{"id":"release"}""", """{
          "id":"release","components":[$component],"componentsLoaded":true,"declarations":[$artifact],"declarationsLoaded":true,
          "publications":[$artifact],"publicationErrors":{"run":"Unavailable"},"needsPolling":true,
          "feedback":{"message":"Refresh failed","failed":true},"labelFeedback":{"message":"Labels unavailable","failed":true},
          "declarationFeedback":{"message":"Declarations unavailable","failed":true},"runFeedback":{"message":"Run unavailable","failed":true}
        }""")
        assertTrue(model.publications.single().stale)
        assertEquals("publication", model.publications.single().publicationId)
        assertEquals("Unavailable", model.publicationErrors["run"])
    }

    @Test fun `release notes retain localized fields and an invalid independent JSON draft`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseNotesModel.serializer(), """{"id":"release"}""", """{
          "id":"release","components":[$component],"componentsLoaded":true,"notes":[$note],"notesLoaded":true,"canManage":true,
          "editing":$note,"editingLabel":"Application 1.0","draft":"{ invalid","draftRevision":3,
          "feedback":{"message":"Invalid JSON","failed":true},"readFeedback":{"message":"Read unavailable","failed":true},
          "componentFeedback":{"message":"Components unavailable","failed":true},"labelFeedback":{"message":"Labels unavailable","failed":true},
          "accessFeedback":{"message":"Permissions unavailable","failed":true}
        }""")
        assertEquals("{ invalid", model.draft)
        assertEquals("Test notes", model.notes.single().localized.single().testFlightWhatToTest)
    }

    @Test fun `environment deployment state retains drift and selected family stops without synthesizing status`() {
        val model = assertWorkOpsSerialization(WorkOpsReleaseEnvironmentsModel.serializer(),
            """{"id":"release","programId":"program"}""", """{
          "id":"release","programId":"program","environments":[{"id":"environment","key":"production","name":"Production","description":"Notes",
            "order":2,"requiresApproval":true,"autoPromote":true,"typeId":"type","sources":["staging"],"version":7}],"loaded":true,
          "states":{"environment":{"deployments":[{"id":"target","projectId":"project","versionId":"old","targetKind":"HELM","status":"DEPLOYED","deployedAt":"2026-11-17T10:00:00Z"}],
            "drift":[{"projectId":"project","deployedVersionId":"old","expectedVersionId":"version","driftType":"VERSION_MISMATCH"}],
            "compatibleProjects":["project"],"loaded":true,"error":"Previous refresh failed"}},
          "selected":{"family":"environment"},"projectNames":{"project":"Application"},"versionNames":{"version":"1.0"},
          "feedback":{"message":"Refresh failed","failed":true},"labelFeedback":{"message":"Labels unavailable","failed":true}
        }""")
        assertEquals("Differs", model.states.getValue("environment").status)
        assertEquals("environment", model.selected["family"])
    }
}
