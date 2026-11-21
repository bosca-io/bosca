package bosca.workops.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkOpsSettingsStateContractTest {
    private val rule = """{"id":"rule","name":"Authored rule","description":"Notes","enabled":false,"scope":"PROJECT","scopeId":"project",
      "trigger":"{\"type\":\"TaskUpdated\",\"fieldKeys\":[\"summary\"]}","conditions":"invalid conditions","actions":"invalid actions",
      "failureMode":"CONTINUE","fires":"8","retention":"60","runAsId":"human","version":7}"""
    private val entry = """{"id":"entry","name":"Authored entry","description":"Notes","color":"#112233","icon":"star",
      "category":"DONE","order":"3","inward":"blocked by","outward":"blocks","version":7}"""
    private val workflow = """{"id":"workflow","name":"Workflow","description":"Notes","initialId":"state","version":7,
      "states":[{"id":"state","name":"Done","category":"DONE","color":"#112233","order":2}],
      "transitions":[{"id":"transition","name":"Finish","description":"Notes","from":["active"],"to":"state"}]}"""
    private val scheme = """{"id":"scheme","name":"Scheme","description":"Notes","defaultId":"workflow","version":7}"""
    private val environment = """{"id":"environment","key":"staging","name":"Staging","description":"Notes","order":2,
      "requiresApproval":true,"autoPromote":true,"typeId":"type","sources":["dev"],"version":7,
      "permissions":[{"groupId":"group","action":"EXECUTE"}],"targetType":"STAGING"}"""
    private val type = """{"id":"type","name":"Staging","description":"Notes","order":2,"version":7}"""

    @Test fun `automation state preserves invalid raw drafts human execution identity and independent history paging`() {
        val model = assertWorkOpsSerialization(WorkOpsAutomationModel.serializer(), "{}", """{
          "scope":"PROJECT","scopeId":"project","offset":25,"targetId":"rule","rows":[$rule],"loaded":true,"editing":true,
          "draft":$rule,"draftRevision":3,"profileName":"Human","deleting":$rule,"resultId":"created","resultScope":"PROGRAM",
          "resultScopeId":"program","historyTarget":$rule,"historyOffset":50,
          "historyRows":[{"id":"log","outcome":"ERROR","started":"2026-11-17T10:00:00Z","finished":"2026-11-17T10:00:01Z",
            "duration":1000,"error":"Execution rejected","taskId":"task"}],"historyLoaded":true,"historyHasNext":true,
          "feedback":{"message":"Invalid raw values","failed":true},"profileFeedback":{"message":"Profile unavailable","failed":true},
          "historyFeedback":{"message":"History unavailable","failed":true},
          "scopeChoices":{"kind":"project","entityId":"project","entities":[{"id":"project","key":"PROJ","name":"Selected project"}],"choicesLoaded":true}
        }""")
        assertEquals("Selected project", model.scopeChoices.selected?.name)
        assertEquals("invalid conditions", model.draft.conditions)
        assertEquals("human", model.draft.runAsId)
        assertEquals(50L, model.historyOffset)
        assertEquals("TaskUpdated", model.draft.triggerType)
    }

    @Test fun `global catalogs retain every authored field and the inspected deletion version`() {
        val model = assertWorkOpsSerialization(WorkOpsGlobalCatalogModel.serializer(), """{"kind":"link-types"}""", """{
          "kind":"link-types","offset":25,"targetId":"entry","rows":[$entry],"loaded":true,"editing":true,
          "draft":$entry,"deleting":$entry,"resultId":"created","feedback":{"message":"Stale entry","failed":true}
        }""")
        assertEquals("blocked by", model.draft.inward)
        assertEquals("blocks", model.draft.outward)
        assertEquals(7L, model.deleting?.version)
    }

    @Test fun `workflow schemes retain default selection without changing state or transition definitions`() {
        val model = assertWorkOpsSerialization(WorkOpsWorkflowsModel.serializer(), "{}", """{
          "kind":"schemes","offset":25,"targetId":"scheme","workflows":[$workflow],"schemes":[$scheme],"loaded":true,"editing":true,
          "draft":{"kind":"schemes","id":"scheme","name":"Authored scheme","description":"Notes","defaultId":"workflow","version":7},
          "deleting":{"kind":"schemes","id":"scheme","name":"Scheme","description":"Notes","defaultId":"workflow","version":7},
          "detail":$workflow,"resultId":"created","resultKind":"schemes","feedback":{"message":"Stale scheme","failed":true}
        }""")
        assertEquals("workflow", model.draft.defaultId)
        assertEquals(listOf("active"), model.detail?.transitions?.single()?.from)
        assertEquals(7L, model.draft.version)
    }

    @Test fun `SLA settings retain structured calendars goal rules and invalid editor text separately`() {
        val policy = """{"id":"policy","name":"Policy","description":"Notes","goals":[{"id":"goal","name":"Respond","minutes":95,
          "risk":80,"start":"created","stop":"done","pause":"waiting","order":2}]}"""
        val calendar = """{"id":"calendar","name":"Calendar","description":"Notes","timeZone":"UTC",
          "weeklyHours":{"MONDAY":[{"startLocal":"09:00","endLocal":"17:00"}]},"holidays":["2026-12-25"]}"""
        val model = assertWorkOpsSerialization(WorkOpsSlaModel.serializer(), "{}", """{
          "kind":"calendars","offset":25,"targetId":"calendar","policies":[$policy],"calendars":[$calendar],"loaded":true,"editing":true,
          "draft":{"name":"Authored calendar","description":"Notes","timeZone":"UTC","weeklyHours":"{ invalid","holidays":"[ invalid"},
          "draftRevision":3,"policyDetail":$policy,"calendarDetail":$calendar,"resultId":"created",
          "feedback":{"message":"Invalid JSON","failed":true}
        }""")
        assertEquals("{ invalid", model.draft.weeklyHours)
        assertEquals("1h 35m", model.policyDetail?.goals?.single()?.duration)
        assertEquals("UTC", model.calendarDetail?.timeZone)
    }

    @Test fun `permissions keep direct group grants and independent failed choice reads`() {
        val model = assertWorkOpsSerialization(WorkOpsPermissionsModel.serializer(), "{}", """{
          "kind":"project","entityId":"project","offset":25,"targetId":"group-EDIT",
          "entities":[{"id":"project","key":"APP","name":"Application"}],"choicesLoaded":true,
          "groups":[{"value":"group","label":"Group","disabled":true}],"groupsLoaded":true,
          "rows":[{"groupId":"group","groupName":"Group","action":"EDIT"}],"loaded":true,"editing":true,"groupDraft":"group","actionDraft":"EDIT",
          "deleting":{"groupId":"group","groupName":"Group","action":"EDIT"},"resultId":"created",
          "feedback":{"message":"Grant rejected","failed":true},"choiceFeedback":{"message":"Entity unavailable","failed":true},
          "groupFeedback":{"message":"Group unavailable","failed":true}
        }""")
        assertEquals("group-EDIT", model.deleting?.key)
        assertEquals("group", model.groupDraft)
    }

    @Test fun `program environment state retains manual keys promotion rules grants and optimistic version`() {
        val model = assertWorkOpsSerialization(WorkOpsEnvironmentsModel.serializer(), "{}", """{
          "programId":"program","offset":25,"targetId":"environment","programs":[{"id":"program","name":"Program"}],
          "rows":[$environment],"types":[$type],"loaded":true,"editing":$environment,"creating":true,
          "draft":{"name":"Authored environment","key":"staging","description":"Notes","typeId":"type","autoPromote":true,"manualKey":true},
          "deleting":$environment,"groups":[{"value":"group","label":"Group"}],"groupsLoaded":true,"groupDraft":"group","resultId":"created",
          "feedback":{"message":"Stale environment","failed":true},"groupFeedback":{"message":"Group unavailable","failed":true}
        }""")
        assertEquals(7L, model.editing?.version)
        assertEquals(listOf("dev"), model.rows.single().sources)
        assertEquals("EXECUTE", model.rows.single().permissions.single().action)
    }

    @Test fun `environment type state preserves ordering and the inspected delete identity`() {
        val model = assertWorkOpsSerialization(WorkOpsEnvironmentTypesModel.serializer(), "{}", """{
          "rows":[$type],"loaded":true,"offset":25,"targetId":"type","editing":$type,"creating":true,
          "draft":{"name":"Authored type","description":"Notes","order":"3"},"deleting":$type,"resultId":"created",
          "feedback":{"message":"Stale type","failed":true}
        }""")
        assertEquals("3", model.draft.order)
        assertFalse(model.feedback.message.isBlank())
        assertEquals(7L, model.deleting?.version)
    }
}
