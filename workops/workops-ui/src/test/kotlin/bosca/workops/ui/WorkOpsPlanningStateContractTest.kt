package bosca.workops.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkOpsPlanningStateContractTest {
    private val planningRow = """{"id":"project","key":"APP","name":"Application","description":"Authored description",
      "ownerId":"owner","ownerName":"Owner","parentId":"program","parentName":"Program","archived":true,
      "modified":"2026-11-17","version":7,"programCount":2,"startDate":"2026-11-01","targetDate":"2026-12-01",
      "defaultTaskTypeSchemeId":"scheme","taskCreationFormSchemaKey":"custom-task","originalOwnerId":"previous-owner"}"""
    private val project = """{"id":"project","key":"APP","name":"Application","description":"Authored description",
      "ownerId":"owner","ownerName":"Owner","programId":"program","archived":true,
      "created":"2026-11-01","modified":"2026-11-17","version":7}"""
    private val board = """{"id":"board","name":"Board","type":"SCRUM","projectId":"project",
      "programId":null,"portfolioId":null,"projectIds":["project"]}"""
    private val version = """{"id":"version","name":"Version","description":"Authored notes","version":7,
      "released":true,"archived":true,"date":"2026-12-01","mode":"PROJECT_LEAD","color":"#112233",
      "startDate":"2026-11-01","sequenceNumber":2,"leadProfileId":"lead","defaultAssigneeProfileId":"assignee"}"""
    private val sprint = """{"id":"sprint","name":"Sprint","state":"ACTIVE","goal":"Authored goal",
      "startDate":"2026-11-01","endDate":"2026-11-15","completeDate":null,"committedIds":["task"],"addedIds":["added"],
      "velocity":5.5,"boardId":"board","boardName":"Board","boardType":"SCRUM","version":7,
      "created":"2026-10-31","modified":"2026-11-01"}"""
    private val sprintTask = """{"id":"task","key":"APP-7","summary":"Authored task","version":9,"sprintId":"sprint",
      "status":"Done","category":"DONE","priority":"High","priorityColor":"#cc1122","assigneeId":"private",
      "assigneeName":"Unavailable","added":true,"available":false}"""

    @Test fun `planning state preserves archived selection move targets and custom project forms`() {
        val model = assertWorkOpsSerialization(WorkOpsPlanningModel.serializer(), """{"kind":"project"}""", """{
          "kind":"project","tab":"archived","offset":25,"rows":[$planningRow],"hasNext":true,"parents":[$planningRow],
          "editing":true,"draft":$planningRow,"archiveTarget":$planningRow,"moveTarget":$planningRow,
          "moveProgramId":"new-program","initialParentId":"program","feedback":{"message":"Stale","failed":true}
        }""")
        assertEquals(7L, model.draft.version)
        assertEquals("custom-task", model.draft.taskCreationFormSchemaKey)
        assertEquals("previous-owner", model.draft.originalOwnerId)
    }

    @Test fun `project detail state retains selected board independent sprint counts and move identity`() {
        val model = assertWorkOpsSerialization(WorkOpsProjectDetailModel.serializer(), """{"id":"project"}""", """{
          "id":"project","tab":"boards","selectedBoardId":"board","project":$project,"programs":[$planningRow],"boards":[$board],
          "sprintCount":3,"activeSprintCount":1,"overviewLoaded":true,"moveTarget":$project,"moveProgramId":"new-program",
          "moved":true,"feedback":{"message":"Move rejected","failed":true}
        }""")
        assertEquals("board", model.selectedBoardId)
        assertEquals(7L, model.moveTarget?.version)
    }

    @Test fun `catalog state preserves version dates component assignments and inspected targets`() {
        val model = assertWorkOpsSerialization(WorkOpsProjectCatalogModel.serializer(),
            """{"projectId":"project","kind":"versions"}""", """{
          "projectId":"project","kind":"versions","offset":25,"targetId":"version","rows":[$version],"loaded":true,"creating":true,
          "nameDraft":"Authored version","descriptionDraft":"Notes","modeDraft":"PROJECT_LEAD","colorDraft":"#112233",
          "deleteTarget":$version,"releaseTarget":$version,"resultId":"new-version","feedback":{"message":"Stale","failed":true},
          "delivery":true,"startDateDraft":"2026-11-01","releaseDateDraft":"2026-12-01","settings":true,
          "pageDirectory":{"projectId":"project","projects":[{"id":"project","key":"PROJ","name":"Selected project"}],"loaded":true}
        }""")
        assertEquals("Selected project", model.pageDirectory?.selected?.name)
        assertEquals("2026-11-01", model.startDateDraft)
        assertEquals("assignee", model.rows.single().defaultAssigneeProfileId)
        assertEquals("Released", model.rows.single().state)
    }

    @Test fun `milestone state retains a failed create and the inspected close version`() {
        val model = assertWorkOpsSerialization(WorkOpsMilestonesModel.serializer(), "{}", """{
          "baseHref":"/workops/programs/program/milestones","programId":"program","offset":25,"creating":true,
          "nameDraft":"Authored milestone","descriptionDraft":"Notes","dateDraft":"2026-12-01","createProgramId":"program",
          "closingId":"milestone","closingName":"Milestone","closeVersion":7,"createdId":"created",
          "feedback":{"message":"Close rejected","failed":true}
        }""")
        assertEquals(7L, model.closeVersion)
        assertEquals("2026-12-01", model.dateDraft)
    }

    @Test fun `board directory state retains multi-project selections and swimlane configuration`() {
        val model = assertWorkOpsSerialization(WorkOpsBoardDirectoryModel.serializer(), "{}", """{
          "projectId":"project","boardId":"board","projects":[$planningRow],"boards":[$board],"creating":true,
          "nameDraft":"Authored board","typeDraft":"SCRUM","scopeDraft":"multi","swimlaneDraft":"ASSIGNEE",
          "projectDraft":["project","other"],"createdId":"created","feedback":{"message":"Rejected","failed":true}
        }""")
        assertEquals(listOf("project", "other"), model.projectDraft)
        assertEquals("ASSIGNEE", model.swimlaneDraft)
    }

    @Test fun `board configuration keeps original mappings separately from edited settings`() {
        val model = assertWorkOpsSerialization(WorkOpsBoardConfigurationModel.serializer(), """{"id":"board"}""", """{
          "id":"board","tab":"columns","settings":{"name":"Board","type":"SCRUM","swimlane":"ASSIGNEE",
            "projectIds":["project","new"],"originalProjectIds":["project"],"originalSwimlane":"NONE","version":7,"multiProject":true},
          "columnDraft":{"id":"column","name":"Active","order":2,"statusIds":["active"],"limit":"5","originalStatusIds":["old"]},
          "deleteTarget":{"id":"column","name":"Active","order":2,"statusIds":["active"],"wipLimit":5},
          "columnsLoaded":true,"feedback":{"message":"Mapping rejected","failed":true}
        }""")
        assertEquals(listOf("old"), model.columnDraft?.originalStatusIds)
        assertEquals(listOf("project"), model.settings?.originalProjectIds)
        assertEquals(7L, model.settings?.version)
    }

    @Test fun `board state preserves pending move versions resolution and independent quick add drafts`() {
        val model = assertWorkOpsSerialization(WorkOpsBoardViewModel.serializer(), """{"id":"board"}""", """{
          "id":"board","cards":[{"id":"task","key":"APP-7","summary":"Authored task","version":9,"projectId":"project",
            "statusId":"active","statusName":"Active","priority":"High","assigneeId":"private","assigneeName":null,
            "transitions":[{"id":"transition","name":"Finish","statusId":"done","category":"DONE"}]}],
          "sprints":[{"id":"sprint","name":"Sprint","state":"ACTIVE","endDate":"2026-11-15","taskIds":["task"]}],
          "sprintId":"sprint","truncated":true,"pendingMove":{"cardId":"task","columnId":"done","label":"Finish","transitionId":"transition","version":9},
          "resolutionId":"resolution","moveSequence":3,"moveResult":"Rejected","feedback":{"message":"Stale card","failed":true},
          "instance":"project-board","adding":true,"summaryDraft":"New task","projectDraft":"project","createdId":"created","cardsLoaded":true
        }""")
        assertEquals(9L, model.pendingMove?.version)
        assertEquals("Profile unavailable", model.cards.single().assigneeLabel)
        assertEquals("New task", model.summaryDraft)
    }

    @Test fun `sprint start state keeps selected unavailable members and separate search pagination`() {
        val model = assertWorkOpsSerialization(WorkOpsSprintsModel.serializer(), """{"mode":"detail"}""", """{
          "mode":"detail","boardId":"board","sprintId":"sprint","tab":"backlog","state":"closed","offset":25,"memberOffset":50,
          "projectId":"project","boardName":"Board","boardType":"SCRUM","rows":[$sprint],"current":$sprint,"members":[$sprintTask],
          "membersLoaded":true,"creating":true,"nameDraft":"Authored sprint","goalDraft":"Authored goal",
          "startDraft":{"sprint":$sprint,"startDate":"2026-11-01","endDate":"2026-11-15","query":"project = APP","offset":25,
            "selectedIds":["task"],"selectedTasks":[$sprintTask],"candidates":[$sprintTask],"hasNext":true,"ready":true},
          "queryDraft":"status = Done","searchOffset":75,"searchRows":[$sprintTask],"searchHasNext":true,"terminalStatusIds":["done"],
          "resultId":"created","resultLabel":"Created sprint","feedback":{"message":"Start rejected","failed":true}
        }""")
        assertEquals(9L, model.startDraft?.selectedTasks?.single()?.version)
        assertEquals(75L, model.searchOffset)
        assertEquals(50L, model.memberOffset)
    }

    @Test fun `sprint completion state preserves per-task destinations and a fractional velocity`() {
        val model = assertWorkOpsSerialization(WorkOpsSprintCloseDraft.serializer(), """{"sprint":$sprint}""", """{
          "sprint":$sprint,"tasks":[$sprintTask],"futures":[{"id":"future","name":"Next sprint"}],
          "destinations":{"task":"future"},"velocity":"5.5","ready":true
        }""")
        assertTrue(model.ready)
        assertEquals("future", model.destinations["task"])
        assertEquals("5.5", model.velocity)
    }

    @Test fun `new sprint start drafts retain empty commitment and require explicit readiness`() {
        val draft = assertWorkOpsSerialization(WorkOpsSprintStartDraft.serializer(), """{
          "sprint":$sprint,"startDate":"2026-11-01","endDate":"2026-11-15"
        }""")
        assertEquals(emptyList(), draft.selectedIds)
        assertEquals(emptyList(), draft.candidates)
        assertEquals(0L, draft.offset)
        assertEquals(false, draft.ready)
        val model = assertWorkOpsSerialization(WorkOpsSprintsModel.serializer(), """{"mode":"detail"}""", """{
          "mode":"detail","sprintId":"sprint","current":$sprint,
          "closeDraft":{"sprint":$sprint,"tasks":[$sprintTask],"destinations":{"task":"backlog"},"velocity":"5.5","ready":true}
        }""")
        assertEquals("backlog", model.closeDraft?.destinations?.get("task"))
    }
}
