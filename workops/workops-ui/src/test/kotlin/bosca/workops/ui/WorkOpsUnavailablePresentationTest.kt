package bosca.workops.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkOpsUnavailablePresentationTest {
    @Test fun `unloaded project tabs retain navigation while dependent catalogs remain unavailable`() {
        val page = WorkOpsProjectDetailModel("project")
        assertEquals("Project unavailable", page.title)
        assertFalse(page.overviewLoaded)
        assertEquals(emptyList(), page.recentRows)
        assertEquals(emptyList(), page.versions)
        assertEquals(emptyList(), page.components)
        assertEquals(emptyList(), page.labels)
        assertNull(page.selectedBoard)
        assertTrue(page.tabs.none { "boardId=" in it.href })
        page.selectedBoardId = "selected&board"
        assertTrue(page.tabs.all { "boardId=selected%26board" in it.href })
        page.feedback.failed = true
        assertEquals("Could not load project", page.title)
    }

    @Test fun `unloaded board and document lists do not enable actions or expose stale choices`() {
        val settings = WorkOpsBoardConfigurationModel("board")
        assertEquals("Board unavailable", settings.title)
        assertEquals("", settings.subtitle)
        assertEquals(emptyList(), settings.projects)
        assertEquals(emptyList(), settings.statuses)
        assertEquals(emptyList(), settings.columns)
        assertEquals("Status unavailable", settings.statusName("missing"))
        val board = WorkOpsBoardViewModel("board")
        assertEquals(emptyList(), board.resolutions)
        assertEquals(emptyList(), board.columns)
        assertFalse(board.canQuickAdd)
        val spec = WorkOpsSpecDetailModel("spec")
        assertEquals(emptyList(), spec.children)
        assertEquals("", spec.nextChildrenHref)
        assertEquals("neutral", spec.statusTone)
        val requirement = WorkOpsRequirementDetailModel("requirement")
        assertEquals(emptyList(), requirement.comments)
        assertEquals("neutral", requirement.statusTone)
        val environment = WorkOpsEnvironmentsModel(loaded = true)
        assertEquals(emptyList(), environment.visibleGrants)
    }
}
