package bosca.workops.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkOpsPresentationBoundaryTest {
    @Test fun `release lifecycle separates unknown reads terminal native status and partial deployment`() {
        val release = WorkOpsReleaseRecord("release", "program", "Release", statusLoaded = true)
        val page = WorkOpsReleaseDetailModel("release", componentsLoaded = true)
        assertEquals("Status unavailable", page.lifecycle)
        assertEquals("Release unavailable", page.title)
        page.feedback.failed = true
        assertEquals("Could not load release", page.title)
        for ((status, label) in mapOf("" to "Draft", "QUEUED" to "Queued", "RUNNING" to "Deploying", "FAILURE" to "Failed",
            "CANCELLED" to "Cancelled", "SUCCESS" to "Run succeeded", "SKIPPED" to "Skipped", "FUTURE_STATUS" to "Unknown status")) {
            page.release = release.copy(latestStatus = status)
            assertEquals(label, page.lifecycle, status)
        }
        page.release = release
        page.components = listOf(WorkOpsReleaseComponentRecord("project", "version", status = "DEPLOYING"))
        assertEquals("Deploying", page.lifecycle)
        page.components = listOf(WorkOpsReleaseComponentRecord("project", "version", status = "DEPLOYED"))
        assertEquals("Deployed", page.lifecycle)
        page.components += WorkOpsReleaseComponentRecord("other", "version", status = "PENDING")
        assertEquals("Deploying", page.lifecycle)
        assertEquals(1, page.pendingCount)
        page.componentsLoaded = false
        assertEquals("Status unavailable", page.lifecycle)
        page.release = release.copy(released = "2026-11-17")
        assertEquals("Released", page.lifecycle)
    }

    @Test fun `release cards distinguish unavailable unknown skipped and successful native status`() {
        val release = WorkOpsReleaseRecord("release", "program", "Release")
        assertEquals("Status unavailable", release.statusLabel)
        assertEquals("neutral", release.statusTone)
        for ((status, label) in mapOf("" to "Draft", "QUEUED" to "Queued", "RUNNING" to "Deploying", "SUCCESS" to "Deployed",
            "FAILURE" to "Failed", "CANCELLED" to "Cancelled", "SKIPPED" to "Skipped", "FUTURE_STATUS" to "Unknown status")) {
            assertEquals(label, release.copy(statusLoaded = true, latestStatus = status).statusLabel)
        }
        assertEquals("success", release.copy(statusLoaded = true, latestStatus = "SUCCESS").statusTone)
        assertEquals("error", release.copy(statusLoaded = true, latestStatus = "FAILURE").statusTone)
        assertEquals("info", release.copy(statusLoaded = true, latestStatus = "RUNNING").statusTone)
        assertEquals("success", release.copy(released = "2026-11-17").statusTone)
    }

    @Test fun `application health requires both reads and gives fatal then open errors precedence over sessions`() {
        val app = WorkOpsReleaseAppHealth("app")
        app.sessionsLoaded = true
        assertEquals("neutral", app.tone)
        app.errorsLoaded = true
        assertEquals("neutral", app.tone)
        app.sessions = WorkOpsHealthCounter(3, emptyList())
        assertEquals("success", app.tone)
        app.openGroups = 2
        assertEquals("warning", app.tone)
        app.fatalGroups = 1
        assertEquals("error", app.tone)
        app.sessionsLoaded = false
        assertEquals("neutral", app.tone)
        app.sessionsLoaded = true
        app.fatalGroups = 0
        app.openGroups = 0
        app.sessions = WorkOpsHealthCounter(0, emptyList())
        assertEquals("neutral", app.tone)
    }

    @Test fun `service health distinguishes missing counters zero traffic and exact warning thresholds`() {
        val service = WorkOpsReleaseServiceHealth("service", loaded = true)
        fun counts(good: Long?, client: Long?, server: Long?) {
            service.success = good?.let { WorkOpsHealthCounter(it, emptyList()) }
            service.client = client?.let { WorkOpsHealthCounter(it, emptyList()) }
            service.server = server?.let { WorkOpsHealthCounter(it, emptyList()) }
        }
        for ((good, client, server) in listOf(Triple(null, 0L, 0L), Triple(0L, null, 0L), Triple(0L, 0L, null))) {
            counts(good, client, server)
            assertNull(service.total)
            assertNull(service.errorRate)
            assertEquals("neutral", service.tone)
        }
        counts(0, 0, 0)
        assertEquals(0.0, service.errorRate)
        assertEquals("neutral", service.tone)
        counts(100, 0, 0)
        assertEquals("success", service.tone)
        counts(99, 1, 0)
        assertEquals("warning", service.tone)
        assertEquals("1.00%", service.errorRateLabel)
        counts(95, 0, 5)
        assertEquals("error", service.tone)
        service.loaded = false
        assertEquals("neutral", service.tone)
    }

    @Test fun `sprint snapshots retain unavailable task and profile distinctions across status categories`() {
        val task = WorkOpsSprintTask("task", key = "APP-7", summary = "Authored task")
        assertEquals("Authored task", task.label)
        assertEquals("/workops/tasks/APP-7", task.href)
        assertEquals("Unassigned", task.assigneeLabel)
        assertEquals("Profile unavailable", task.copy(assigneeId = "private").assigneeLabel)
        assertEquals("Person", task.copy(assigneeId = "profile", assigneeName = "Person").assigneeLabel)
        assertEquals("Task unavailable", task.copy(available = false).label)
        assertEquals("Details unavailable", task.copy(available = false).assigneeLabel)
        assertEquals("", task.copy(available = false).href)
        assertFalse(task.finished)
        assertEquals("neutral", task.tone)
        assertTrue(task.copy(category = "CANCELLED").finished)
        assertTrue(task.copy(category = "DONE").finished)
        assertEquals("success", task.copy(category = "DONE").tone)
        assertEquals("purple", task.copy(category = "IN_PROGRESS").tone)
    }

    @Test fun `archived catalog rows and unavailable repositories retain their actual presentation contract`() {
        val row = WorkOpsProjectCatalogRow("version", "Version", archived = true)
        assertEquals("Archived", row.state)
        assertEquals("Project lead", row.copy(mode = "PROJECT_LEAD").modeLabel)
        val repository = WorkOpsProjectRepositoryRecord("attachment", "repository", name = "Source")
        assertEquals("Source", repository.label)
        assertEquals("/repositories/repository", repository.href)
        assertEquals("Repository unavailable", repository.copy(available = false).label)
        assertEquals("", repository.copy(available = false).href)
    }

    @Test fun `notification schemes show malformed recipients explicitly while retaining unknown events`() {
        val recipients = Json.parseToJsonElement("""{"CUSTOM":[null,"text",{}, {"type":null},{"type":3},{"type":" "},{"type":"ASSIGNEE"}],"BROKEN":false}""")
        val scheme = WorkOpsNotificationSchemeRecord("scheme", "Scheme", "", recipients)
        val custom = scheme.events.single { it.key == "CUSTOM" }
        assertEquals(List(6) { "Recipient unavailable" } + "ASSIGNEE", custom.recipients)
        assertTrue(custom.available)
        assertFalse(scheme.events.single { it.key == "BROKEN" }.available)
        val unavailable = WorkOpsNotificationSchemeRecord("scheme", "Scheme", "", JsonNull)
        assertFalse(unavailable.available)
        assertTrue(unavailable.events.all { !it.available })
    }
}
