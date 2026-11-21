package bosca.workops.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import bosca.bml.render.decodeClientState
import bosca.bml.render.encodeState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class WorkOpsIndependentDraftTest {
    private fun unavailable(operation: String) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement {
            assertEquals(operation, operationName)
            throw IllegalStateException("Read unavailable")
        }
    })

    @Test fun `a task read failure preserves independent comment work log link and edit drafts across the wire`() = runTest {
        val model = WorkOpsTaskDetailModel("task", tab = "history", activityOffset = 25,
            data = null, editing = true, draft = WorkOpsTaskDraft(summary = "Unsaved title", expectedVersion = 7),
            sprints = listOf(WorkOpsSprintChoice("sprint", "Selected sprint")), sprintName = "Selected sprint",
            profileNames = mapOf("person" to "Person"), pendingTransition = "", commentDraft = "Unsent comment", commentRevision = 3,
            loggingWork = false, workTime = "15m", workStarted = "2026-11-17T10:00", workComment = "Unsent work detail", estimateAdjustment = "5m",
            linking = false, linkTarget = "APP-2", linkType = "relates", document = WorkOpsDocumentModel("document"), requirementsOffset = 50)
        model.retry(unavailable("WorkOpsTaskDetail"))
        val returned = decodeClientState<WorkOpsTaskDetailModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Read unavailable", returned.feedback.message)
        assertEquals("Unsaved title", returned.draft.summary)
        assertEquals(7L, returned.draft.expectedVersion)
        assertTrue(returned.editing)
        assertEquals("Unsent comment", returned.commentDraft)
        assertEquals(3L, returned.commentRevision)
        assertEquals("15m", returned.workTime)
        assertEquals("2026-11-17T10:00", returned.workStarted)
        assertEquals("Unsent work detail", returned.workComment)
        assertEquals("5m", returned.estimateAdjustment)
        assertEquals("APP-2", returned.linkTarget)
        assertEquals("relates", returned.linkType)
        assertEquals("", returned.pendingTransition)
        assertEquals("document", returned.document?.metadataId)
        assertEquals("Person", returned.profileName("person"))
        assertEquals("history", returned.tab)
        assertEquals(25L, returned.activityOffset)
        assertEquals(50L, returned.requirementsOffset)
    }

    @Test fun `a specification read failure retains title owner comment transition and independent page state`() = runTest {
        val model = WorkOpsSpecDetailModel("spec", tab = "history", offset = 25, childrenOffset = 50, requirementsOffset = 75,
            data = null, document = WorkOpsDocumentModel("document"), parentName = "Parent", profileNames = mapOf("owner" to "Owner"),
            editingOwner = true, ownerDraft = "owner", editVersion = 7, renaming = false, nameDraft = "Unsaved specification",
            pendingTransition = "", transitionVersion = 9, commentDraft = "Unsent comment", commentRevision = 3, generationsOffset = 100)
        model.retry(unavailable("WorkOpsSpecDetail"))
        val returned = decodeClientState<WorkOpsSpecDetailModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Read unavailable", returned.feedback.message)
        assertEquals("Unsaved specification", returned.nameDraft)
        assertFalse(returned.renaming)
        assertEquals("owner", returned.ownerDraft)
        assertEquals(7L, returned.editVersion)
        assertTrue(returned.editingOwner)
        assertEquals("", returned.pendingTransition)
        assertEquals(9L, returned.transitionVersion)
        assertEquals("Unsent comment", returned.commentDraft)
        assertEquals("document", returned.document?.metadataId)
        assertEquals("Owner", returned.profileName("owner"))
        assertEquals(25L, returned.offset)
        assertEquals(50L, returned.childrenOffset)
        assertEquals(75L, returned.requirementsOffset)
        assertEquals(100L, returned.generationsOffset)
    }

    @Test fun `a requirement read failure retains assignment rename comment and linked task transition drafts`() = runTest {
        val model = WorkOpsRequirementDetailModel("requirement", tab = "history", offset = 25, data = null,
            document = WorkOpsDocumentModel("document"), parentName = "Parent", profileNames = mapOf("assignee" to "Assignee"),
            editing = true, priorityDraft = "priority", assigneeDraft = "assignee", editVersion = 7, renaming = false,
            nameDraft = "Unsaved requirement", pendingTransition = "", transitionVersion = 9,
            commentDraft = "Unsent comment", commentRevision = 3)
        model.retry(unavailable("WorkOpsRequirementDetail"))
        val returned = decodeClientState<WorkOpsRequirementDetailModel>(encodeState(model))
        assertTrue(returned.feedback.failed)
        assertEquals("Read unavailable", returned.feedback.message)
        assertEquals("Unsaved requirement", returned.nameDraft)
        assertFalse(returned.renaming)
        assertEquals("priority", returned.priorityDraft)
        assertEquals("assignee", returned.assigneeDraft)
        assertEquals(7L, returned.editVersion)
        assertEquals("", returned.pendingTransition)
        assertEquals(9L, returned.transitionVersion)
        assertEquals("Unsent comment", returned.commentDraft)
        assertEquals("document", returned.document?.metadataId)
        assertEquals("Assignee", returned.profileName("assignee"))
        assertEquals(25L, returned.offset)
    }
}
