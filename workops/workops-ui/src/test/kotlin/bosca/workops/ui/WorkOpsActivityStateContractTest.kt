package bosca.workops.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkOpsActivityStateContractTest {
    @Test fun `task search state preserves diagnostics filters and unavailable assignee identity`() {
        val model = assertWorkOpsSerialization(WorkOpsTaskSearchModel.serializer(), "{}", """{
          "source":"project = APP", "offset":25,
          "rows":[{"id":"task","key":"APP-7","summary":"Draft","status":"Active","statusCategory":"IN_PROGRESS",
            "priority":"High","priorityColor":"#cc1122","taskType":"Task","assignee":"","dueDate":"2026-12-01","assigneeProfileId":"private"}],
          "filters":[{"id":"filter","name":"Specs","source":"owner = me()","path":"/workops/specs"}],
          "errors":["Search unavailable"],"diagnostics":[{"message":"Expected value","start":8,"end":9,"hint":"Choose a value"}],
          "freeTextTerms":["authored phrase"],"hasNext":true,"failed":true
        }""")
        assertFalse(model.valid)
        assertEquals("Profile unavailable", model.rows.single().assigneeLabel)
        assertEquals(2L, model.page)
        assertTrue(model.nextHref.endsWith("offset=50"))
    }

    @Test fun `specification search state keeps counts Git links and an explicit empty query`() {
        val model = assertWorkOpsSerialization(WorkOpsSpecSearchModel.serializer(), "{}", """{
          "source":"", "offset":25,
          "rows":[{"id":"spec","title":"Authored title","status":"Done","category":"DONE","owner":"Owner","project":"APP",
            "childCount":3,"childDoneCount":2,"requirementCount":4,"gitLinked":true,"modified":"2026-11-17T12:00:00Z"}],
          "filters":[{"id":"filter","name":"Tasks","source":"status = Done","path":"/workops/tasks"}],
          "errors":["Unavailable"],"diagnostics":[{"message":"Invalid query","start":0,"end":1,"hint":"Retry"}],
          "freeTextTerms":["retained"],"hasNext":true,"failed":true
        }""")
        assertEquals("", model.source)
        assertEquals(4L, model.rows.single().requirementCount)
        assertEquals("success", model.rows.single().statusTone)
    }

    @Test fun `project selector state keeps custom forms dates and the original owner`() {
        val model = assertWorkOpsSerialization(WorkOpsProjectDirectoryModel.serializer(), "{}", """{
          "projectId":"project", "loaded":true, "feedback":{"message":"Reload failed","failed":true},
          "projects":[{"id":"project","key":"APP","name":"Application","description":"Draft","ownerId":"new-owner","ownerName":"Owner",
            "parentId":"program","parentName":"Program","archived":true,"modified":"2026-11-17","version":7,"programCount":2,
            "startDate":"2026-11-01","targetDate":"2026-12-01","defaultTaskTypeSchemeId":"scheme",
            "taskCreationFormSchemaKey":"custom-task","originalOwnerId":"original-owner"}]
        }""")
        assertEquals("custom-task", model.projects.single().taskCreationFormSchemaKey)
        assertEquals("original-owner", model.projects.single().originalOwnerId)
    }

    @Test fun `notification drafts preserve opt outs unknown channels and hidden mute lists on the wire`() {
        val model = assertWorkOpsSerialization(WorkOpsNotificationDraft.serializer(), "{}", """{
          "watchAuthored":false,"watchCommented":false,"dailyDigest":true,"dndStartLocal":"22:00","dndEndLocal":"07:00",
          "eventChannels":{"UNKNOWN":["CUSTOM"],"TASK_CREATED":[]},"flags":{"TASK_CREATED":{"EMAIL":false}},
          "mutedTaskIds":["private-task"],"mutedProjectIds":["private-project"]
        }""")
        assertFalse(model.watchAuthored)
        assertEquals(setOf("CUSTOM"), model.channelsFor("UNKNOWN"))
        assertEquals(emptySet(), model.channelsFor("TASK_CREATED"))
        assertEquals(listOf("private-task"), model.mutedTaskIds)
    }
}
