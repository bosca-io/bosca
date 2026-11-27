package bosca.workops.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkOpsInboxStateContractTest {
    @Test fun `saved filter state retains diagnostic ranges preview source and optimistic version`() {
        val model = assertWorkOpsSerialization(WorkOpsFiltersModel.serializer(), "{}", """{
          "offset":25,"rows":[{"id":"filter","name":"My work","description":"Notes","source":"assignee = me()","modified":"2026-11-17","version":7}],
          "hasNext":true,"editing":true,"draft":{"id":"filter","name":"Authored filter","description":"Notes","source":"status =","version":7},
          "diagnostics":[{"message":"Expected value","start":8,"end":9,"hint":"Choose a status"}],"previewId":"filter","previewSource":"status = Done",
          "previewRows":[{"id":"task","key":"APP-7","summary":"Authored task","status":"Done","category":"DONE"}],"deletingId":"filter",
          "feedback":{"message":"Invalid query","failed":true}
        }""")
        assertEquals("status =", model.draft.source)
        assertEquals(7L, model.draft.version)
        assertEquals("success", model.previewRows.single().tone)
    }

    @Test fun `inbox state retains read status return destinations and hidden preference values`() {
        val model = assertWorkOpsSerialization(WorkOpsInboxModel.serializer(), "{}", """{
          "tab":"all","offset":25,"rows":[{"id":"notification","event":"TASK_CREATED","body":"Authored body",
            "href":"/workops/tasks/task","read":true,"created":"2026-11-17"}],"unreadCount":3,"hasNext":true,"preferencesLoaded":true,
          "draft":{"watchAuthored":false,"watchCommented":false,"dailyDigest":true,"dndStartLocal":"22:00","dndEndLocal":"07:00",
            "eventChannels":{"UNKNOWN":["CUSTOM"]},"flags":{"TASK_CREATED":{"EMAIL":false}},"mutedTaskIds":["private-task"],"mutedProjectIds":["private-project"]},
          "nextHref":"/workops/tasks/task?tab=history","feedback":{"message":"Mark read rejected","failed":true}
        }""")
        assertTrue(model.rows.single().read)
        assertEquals(listOf("private-project"), model.draft.mutedProjectIds)
    }

    @Test fun `notification settings preserve unknown recipient events without changing API behavior`() {
        val model = assertWorkOpsSerialization(WorkOpsNotificationsModel.serializer(), "{}", """{
          "tab":"preferences","schemeId":"scheme","offset":25,"schemes":[{"id":"scheme","name":"Scheme","description":"Notes",
            "recipients":{"UNKNOWN":[],"TASK_CREATED":[{"type":"ASSIGNEE"}]}}],"loaded":true,
          "preferences":{"tab":"preferences","preferencesLoaded":true,"draft":{"watchAuthored":false,"mutedTaskIds":["private"]}},
          "feedback":{"message":"Scheme unavailable","failed":true}
        }""")
        assertTrue(model.schemes.single().events.any { it.key == "UNKNOWN" })
        assertFalse(model.preferences.draft.watchAuthored)
    }

    @Test fun `platform approval state retains original run node and a rejected approval note`() {
        val model = assertWorkOpsSerialization(WorkOpsApprovalModel.serializer(), """{"id":"run"}""", """{
          "id":"run","run":{"id":"run","status":"WAITING","error":"Previous failure","modified":"2026-11-17",
            "gates":[{"runId":"run","nodeId":"node","prompt":"Approve deployment"}]},"loaded":true,"editing":true,
          "draft":{"runId":"run","nodeId":"node","prompt":"Approve deployment","approved":false,"note":"Human rejection note"},
          "acknowledgement":"Recorded","feedback":{"message":"Approval rejected","failed":true}
        }""")
        assertFalse(model.draft.approved)
        assertEquals("node", model.draft.nodeId)
        assertEquals("Human rejection note", model.draft.note)
    }
}
