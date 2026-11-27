package bosca.workops.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkOpsAuthoringStateContractTest {
    @Test fun `task state retains independent drafts and inspected versions with omitted defaults`() {
        val model = assertWorkOpsSerialization(WorkOpsTaskDetailModel.serializer(), """{"id":"task"}""", """{
          "id":"task","tab":"history","activityOffset":25,"editing":true,
          "draft":{"summary":"Authored title","description":"Authored body","priorityId":"priority","assigneeId":"profile",
            "sprintId":"sprint","dueDate":"2026-12-01","originalEstimate":"2h","remainingEstimate":"1h","expectedVersion":7},
          "sprints":[{"id":"sprint","name":"Sprint"}],"sprintName":"Sprint","profileNames":{"profile":"Person"},
          "pendingTransition":"transition","commentDraft":"Unsent comment","commentRevision":3,"loggingWork":true,
          "workTime":"15m","workStarted":"2026-11-17T10:00","workComment":"Unsent work","estimateAdjustment":"5m",
          "linking":true,"linkTarget":"APP-2","linkType":"relates","feedback":{"message":"Stale task","failed":true},
          "document":{"metadataId":"document","savedRevision":"revision","feedback":{"message":"Save failed","failed":true}},
          "requirementsOffset":50
        }""")
        assertEquals(7L, model.draft.expectedVersion)
        assertEquals("Unsent comment", model.commentDraft)
        assertEquals(50L, model.requirementsOffset)
    }

    @Test fun `specification state preserves separate page offsets title owner and comment revisions`() {
        val model = assertWorkOpsSerialization(WorkOpsSpecDetailModel.serializer(), """{"id":"spec"}""", """{
          "id":"spec","tab":"history","offset":25,"childrenOffset":50,"requirementsOffset":75,
          "document":{"metadataId":"document","savedRevision":"revision"},"parentName":"Parent","profileNames":{"owner":"Owner"},
          "editingOwner":true,"ownerDraft":"owner","editVersion":7,"renaming":true,"nameDraft":"Unsaved title",
          "pendingTransition":"transition","transitionVersion":9,"commentDraft":"Unsent comment","commentRevision":3,
          "feedback":{"message":"Stale specification","failed":true},"generationsOffset":100
        }""")
        assertEquals(7L, model.editVersion)
        assertEquals(9L, model.transitionVersion)
        assertEquals(100L, model.generationsOffset)
    }

    @Test fun `requirement state preserves private assignment and independent Content rename state`() {
        val model = assertWorkOpsSerialization(WorkOpsRequirementDetailModel.serializer(), """{"id":"requirement"}""", """{
          "id":"requirement","tab":"history","offset":25,"document":{"metadataId":"document","savedRevision":"revision"},
          "parentName":"Parent","profileNames":{"private":"Unavailable"},"editing":true,"priorityDraft":"priority",
          "assigneeDraft":"private","editVersion":7,"renaming":true,"nameDraft":"Authored name",
          "pendingTransition":"transition","transitionVersion":9,"commentDraft":"Unsent comment","commentRevision":3,
          "feedback":{"message":"Stale requirement","failed":true}
        }""")
        assertEquals("private", model.assigneeDraft)
        assertEquals("Authored name", model.nameDraft)
    }

    @Test fun `configured task forms preserve JSON values provider choices and reload revisions`() {
        val model = assertWorkOpsSerialization(WorkOpsTaskCreateModel.serializer(), "{}", """{
          "open":true,"projectId":"project","draft":{"summary":"Authored task","custom":{"enabled":false},"upload":null},
          "definition":{"key":"custom-task","name":"Custom task","version":7,"schema":{"type":"object"},"uiSchema":{"layout":[]}},
          "options":{"owner":[{"value":"private","label":"Unavailable"}]},"error":"Submission rejected","createdId":"task","reloadRevision":3
        }""")
        assertEquals("custom-task", model.definition?.key)
        assertEquals(3L, model.reloadRevision)
        assertEquals("Unavailable", model.options.getValue("owner").single().label)
    }

    @Test fun `new specifications retain their parent and selected project after a failed create`() {
        val model = assertWorkOpsSerialization(WorkOpsSpecCreateModel.serializer(), "{}", """{
          "creating":true,"projectId":"project","parentId":"parent","parentName":"Parent","nameDraft":"Authored title",
          "createdId":"spec","refreshHref":"/workops/specs?offset=25","feedback":{"message":"Rejected","failed":true}
        }""")
        assertEquals("parent", model.parentId)
        assertEquals("Authored title", model.nameDraft)
    }

    @Test fun `requirement lists retain their parent kind row versions and off-page creation result`() {
        val model = assertWorkOpsSerialization(WorkOpsRequirementsModel.serializer(),
            """{"parentId":"spec","parentHref":"/workops/specs/spec"}""", """{
          "parentId":"spec","parentHref":"/workops/specs/spec","offset":25,
          "data":[{"id":"requirement","metadataId":"document","version":7,"sortOrder":2,"status":"Active","category":"IN_PROGRESS",
            "priority":"High","priorityColor":"#cc1122","assigneeId":"private","assignee":null}],
          "names":{"document":"Authored requirement"},"creating":true,"nameDraft":"Draft","editingId":"requirement",
          "assigneeDraft":"private","assigneeName":"Unavailable","editVersion":7,"feedback":{"message":"Stale","failed":true},
          "createdId":"new-requirement","parentType":"SPEC","revision":3
        }""")
        assertEquals(7L, model.data.single().version)
        assertEquals("new-requirement", model.createdId)
    }

    @Test fun `specification contexts preserve authored labels and reference destinations`() {
        val model = assertWorkOpsSerialization(WorkOpsSpecContextsModel.serializer(),
            """{"specId":"spec","returnTo":"/workops/specs/spec"}""", """{
          "specId":"spec","returnTo":"/workops/specs/spec?tab=contexts",
          "rows":[{"id":"context","kind":"METADATA","title":"Authored label","href":"/workops/references/content/document","created":"2026-11-17"}],
          "adding":true,"kind":"METADATA","targetDraft":"document","targetName":"Document","labelDraft":"Authored label",
          "feedback":{"message":"Reference unavailable","failed":true},"revision":3
        }""")
        assertEquals("Authored label", model.labelDraft)
        assertEquals("document", model.targetDraft)
    }

    @Test fun `generation state keeps inspected versions and authorized task labels`() {
        val model = assertWorkOpsSerialization(WorkOpsSpecGenerationsModel.serializer(),
            """{"specId":"spec","returnTo":"/workops/specs/spec"}""", """{
          "specId":"spec","returnTo":"/workops/specs/spec?tab=generations","offset":25,
          "taskNames":{"task":"Authored task"},"versionDraft":"7","feedback":{"message":"Rejected","failed":true}
        }""")
        assertEquals("7", model.versionDraft)
        assertEquals("Authored task", model.taskNames["task"])
    }

    @Test fun `Git push state retains the human author and saved commit result`() {
        val model = assertWorkOpsSerialization(WorkOpsSpecGitModel.serializer(), """{"specId":"spec"}""", """{
          "specId":"spec","repositoryName":"Workspace","pushing":true,"authorNameDraft":"Human Author",
          "authorEmailDraft":"human@example.test","commitSha":"saved-sha","feedback":{"message":"Push rejected","failed":true}
        }""")
        assertTrue(model.pushing)
        assertEquals("Human Author", model.authorNameDraft)
        assertEquals("saved-sha", model.commitSha)
    }
}
