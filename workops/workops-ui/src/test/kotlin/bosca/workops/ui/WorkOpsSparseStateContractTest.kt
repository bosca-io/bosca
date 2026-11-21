package bosca.workops.ui

import bosca.workops.ui.collaboration.WorkOpsCollaborationEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkOpsSparseStateContractTest {
    @Test fun `collaboration event serialization retains its source kind and signed presence bytes`() {
        val event = assertWorkOpsSerialization(WorkOpsCollaborationEvent.serializer(), """{"source":"first","kind":"state"}""",
            """{"source":"second","kind":"presence","presence":[0,-1,127]}""")
        assertEquals("second", event.source)
        assertEquals("presence", event.kind)
        kotlin.test.assertContentEquals(byteArrayOf(0, -1, 127), event.presence)
    }
    @Test fun `new automation drafts use authored defaults and execution logs retain unknown completion`() {
        val rule = assertWorkOpsSerialization(WorkOpsAutomationRuleRecord.serializer(), "{}")
        assertTrue(rule.enabled)
        assertEquals("GLOBAL", rule.scope)
        assertEquals("TaskCreated", rule.triggerType)
        assertEquals("STOP_ON_ERROR", rule.failureMode)
        assertEquals("5", rule.fires)
        assertEquals("30", rule.retention)
        val log = assertWorkOpsSerialization(WorkOpsAutomationLogRecord.serializer(), """{"id":"log","outcome":"OK","started":"2026-11-17"}""")
        assertNull(log.duration)
        assertEquals("", log.finished)
        assertEquals("", log.taskHref)
    }

    @Test fun `minimal sprint and board drafts keep empty assignments and distinguish available members`() {
        val task = assertWorkOpsSerialization(WorkOpsSprintTask.serializer(), """{"id":"task"}""")
        assertTrue(task.available)
        assertFalse(task.added)
        assertNull(task.sprintId)
        assertNull(task.assigneeId)
        val column = assertWorkOpsSerialization(WorkOpsBoardColumnDraft.serializer(), "{}")
        assertEquals(1, column.order)
        assertEquals(emptyList(), column.originalStatusIds)
        assertEquals("", column.limit)
        val taskDraft = assertWorkOpsSerialization(WorkOpsTaskDraft.serializer(), "{}")
        assertEquals(0L, taskDraft.expectedVersion)
        assertEquals("", taskDraft.remainingEstimate)
    }

    @Test fun `minimal catalog and workflow facts do not invent release dates owners or states`() {
        val catalog = assertWorkOpsSerialization(WorkOpsProjectCatalogRow.serializer(), """{"id":"version","name":"Version"}""")
        assertFalse(catalog.released)
        assertFalse(catalog.archived)
        assertNull(catalog.leadProfileId)
        assertEquals("Unreleased", catalog.state)
        val workflow = assertWorkOpsSerialization(WorkOpsWorkflowRecord.serializer(), """{"id":"workflow","name":"Workflow"}""")
        assertEquals(emptyList(), workflow.states)
        assertEquals(emptyList(), workflow.transitions)
        val draft = assertWorkOpsSerialization(WorkOpsWorkflowDraft.serializer(), "{}")
        assertEquals("workflow", draft.label)
        val entry = assertWorkOpsSerialization(WorkOpsGlobalCatalogEntry.serializer(), "{}")
        assertEquals("0", entry.order)
    }

    @Test fun `new environment and SLA drafts preserve default timezone manual-key and ordering policies`() {
        val environment = assertWorkOpsSerialization(WorkOpsEnvironmentDraft.serializer(), "{}")
        assertFalse(environment.autoPromote)
        assertFalse(environment.manualKey)
        val type = assertWorkOpsSerialization(WorkOpsEnvironmentTypeDraft.serializer(), "{}")
        assertEquals("0", type.order)
        val record = assertWorkOpsSerialization(WorkOpsEnvironmentTypeRecord.serializer(), """{"id":"type","name":"Type"}""")
        assertEquals(0L, record.version)
        val sla = assertWorkOpsSerialization(WorkOpsSlaDraft.serializer(), "{}")
        assertEquals("America/New_York", sla.timeZone)
        assertEquals("[]", sla.holidays)
    }

    @Test fun `new release drafts and sparse artifact declarations preserve unset and expected values`() {
        val release = assertWorkOpsSerialization(WorkOpsReleaseDraft.serializer(), "{}")
        assertEquals(1, release.step)
        assertEquals(emptyList(), release.components)
        val version = assertWorkOpsSerialization(WorkOpsReleaseVersionDraft.serializer(), "{}")
        assertEquals("", version.startDate)
        assertEquals("", version.releaseDate)
        val artifact = assertWorkOpsSerialization(WorkOpsReleaseArtifactRecord.serializer(),
            """{"projectId":"project","type":"oci","namespace":"images","coordinate":"application:1.0"}""")
        assertEquals("EXPECTED", artifact.status)
        assertEquals(emptyList(), artifact.environments)
        assertFalse(artifact.stale)
        val component = assertWorkOpsSerialization(WorkOpsReleaseComponentChoice.serializer(),
            """{"projectId":"project","projectKey":"APP","projectName":"Application","versionId":"version","versionName":"1.0"}""")
        assertFalse(component.bundled)
        assertEquals("", component.error)
    }

    @Test fun `sparse telemetry retains unknown counts and never displays them as healthy zeroes`() {
        val app = assertWorkOpsSerialization(WorkOpsReleaseAppHealth.serializer(), """{"identifier":"io.app"}""")
        assertNull(app.sessions)
        assertNull(app.openGroups)
        assertNull(app.fatalGroups)
        assertEquals("neutral", app.tone)
        val service = assertWorkOpsSerialization(WorkOpsReleaseServiceHealth.serializer(), """{"identifier":"api"}""")
        assertNull(service.total)
        assertNull(service.errorRate)
        assertEquals("Unavailable", service.errorRateLabel)
        assertEquals("neutral", service.tone)
        val source = assertWorkOpsSerialization(WorkOpsReleaseTelemetrySource.serializer(),
            """{"projectId":"project","projectLabel":"Application","identifier":"io.app"}""")
        assertEquals("", source.versionId)
        val state = assertWorkOpsSerialization(WorkOpsReleaseEnvironmentState.serializer(), "{}")
        assertEquals("Unavailable", state.status)
    }

    @Test fun `minimal approval filter and repository drafts preserve their distinct defaults`() {
        val approval = assertWorkOpsSerialization(WorkOpsApprovalDraft.serializer(), "{}")
        assertTrue(approval.approved)
        assertEquals("", approval.note)
        val filter = assertWorkOpsSerialization(WorkOpsSavedFilterDraft.serializer(), "{}")
        assertEquals(0L, filter.version)
        val repository = assertWorkOpsSerialization(WorkOpsProjectRepositoryRecord.serializer(), """{"id":"attachment","repositoryId":"repository"}""")
        assertTrue(repository.available)
        assertFalse(repository.archived)
        val action = assertWorkOpsSerialization(WorkOpsReleaseCiAction.serializer(), """{"kind":"cancel"}""")
        assertEquals("", action.jobId)
        assertEquals("", action.environment)
    }

    @Test fun `notification event availability survives default and explicitly unavailable wire records`() {
        val event = assertWorkOpsSerialization(WorkOpsNotificationEventRecord.serializer(),
            """{"key":"TASK_CREATED","recipients":[]}""", """{"key":"CUSTOM","recipients":["Unavailable recipient"],"available":false}""")
        assertFalse(event.available)
        assertEquals(listOf("Unavailable recipient"), event.recipients)
    }
}
