package bosca.pipelines

import bosca.pipelines.builtin.ApprovalGateNode
import bosca.pipelines.builtin.CastNode
import bosca.pipelines.builtin.DispatchEventNode
import bosca.pipelines.builtin.ExecuteJobNode
import bosca.pipelines.builtin.ExecuteScriptNode
import bosca.pipelines.builtin.FlattenNode
import bosca.pipelines.builtin.ForEach
import bosca.pipelines.builtin.GetFormNode
import bosca.pipelines.builtin.GetFormSubmissionNode
import bosca.pipelines.builtin.GetIdNode
import bosca.pipelines.builtin.JsonataNode
import bosca.pipelines.builtin.RenderEmailTemplateNode
import bosca.pipelines.builtin.RunPipelineNode
import bosca.pipelines.builtin.SendEmailTemplateNode
import bosca.pipelines.builtin.SendSlackNode
import bosca.pipelines.builtin.SendWebhookNode
import bosca.pipelines.builtin.StatusNode
import bosca.pipelines.builtin.SwitchNode
import bosca.pipelines.builtin.SwitchCase
import bosca.pipelines.builtin.ToStringNode
import bosca.pipelines.builtin.ToUuidNode
import bosca.pipelines.builtin.ThrowNode
import bosca.pipelines.builtin.WaitForInputNode
import bosca.pipelines.builtin.WaitUntilNode
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.model.NodeGroup
import bosca.pipelines.model.BrokenPipeline
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineAwaitingApproval
import bosca.pipelines.model.PipelineGraph
import bosca.pipelines.model.PipelineNamedShape
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.model.PipelineRunUpdate
import bosca.pipelines.model.PipelineTriggersChanged
import bosca.pipelines.model.RunAwaitingNode
import bosca.pipelines.model.RunStep
import bosca.pipelines.model.RunStepKind
import bosca.pipelines.model.RunStepStatus
import bosca.pipelines.node.ShapeField
import bosca.pipelines.node.NodePosition
import bosca.pipelines.repository.PipelineRecord
import bosca.pipelines.repository.PipelinePermission
import bosca.pipelines.repository.PipelineRunIteration
import bosca.pipelines.repository.PipelineShapeRecord
import bosca.pipelines.service.PipelineRunNodeResult
import bosca.pipelines.trigger.ExecuteNodeInJob
import bosca.pipelines.trigger.PipelineChildRunJob
import bosca.pipelines.trigger.PipelineDelayJob
import bosca.pipelines.trigger.PipelineDispatchJob
import bosca.pipelines.trigger.PipelineManualRunJob
import bosca.pipelines.trigger.PipelineRunJob
import bosca.pipelines.trigger.PipelineScheduledRunJob
import bosca.pipelines.trigger.PipelineRetentionSweepJob
import bosca.pipelines.trigger.PipelineSuspendedSweepJob
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SerializationDefaultsCoverageTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        }
    }
    private val encodeDefaultsJson = Json(json) { encodeDefaults = true }

    private fun <T> exerciseEncoding(serializer: KSerializer<T>, minimal: T, complete: T) {
        assertTrue(json.encodeToString(serializer, minimal).isNotBlank())
        assertTrue(json.encodeToString(serializer, complete).isNotBlank())
        assertTrue(encodeDefaultsJson.encodeToString(serializer, minimal).isNotBlank())
    }

    @Test
    fun `serializable contracts reject payloads missing required fields`() {
        val missingRequiredFields = listOf<() -> Unit>(
            { json.decodeFromString(StatusNode.serializer(), "{}") },
            { json.decodeFromString(WaitForInputNode.serializer(), "{}") },
            { json.decodeFromString(ApprovalGateNode.serializer(), "{}") },
            { json.decodeFromString(ThrowNode.serializer(), "{}") },
            { json.decodeFromString(RenderEmailTemplateNode.serializer(), "{}") },
            { json.decodeFromString(SendEmailTemplateNode.serializer(), "{}") },
            { json.decodeFromString(SendWebhookNode.serializer(), "{}") },
            { json.decodeFromString(SendSlackNode.serializer(), "{}") },
            { json.decodeFromString(FlattenNode.serializer(), "{}") },
            { json.decodeFromString(DispatchEventNode.serializer(), "{}") },
            { json.decodeFromString(CastNode.serializer(), "{}") },
            { json.decodeFromString(JsonataNode.serializer(), "{}") },
            { json.decodeFromString(ToUuidNode.serializer(), "{}") },
            { json.decodeFromString(ToStringNode.serializer(), "{}") },
            { json.decodeFromString(GetFormNode.serializer(), "{}") },
            { json.decodeFromString(GetFormSubmissionNode.serializer(), "{}") },
            { json.decodeFromString(GetIdNode.serializer(), "{}") },
            { json.decodeFromString(ExecuteJobNode.serializer(), "{}") },
            { json.decodeFromString(ExecuteScriptNode.serializer(), "{}") },
            { json.decodeFromString(RunPipelineNode.serializer(), "{}") },
            { json.decodeFromString(ForEach.serializer(), "{}") },
            { json.decodeFromString(WaitUntilNode.serializer(), "{}") },
            { json.decodeFromString(SwitchNode.serializer(), "{}") },
            { json.decodeFromString(NodeGroup.serializer(), "{}") },
            { json.decodeFromString(RunStep.serializer(), "{}") },
            { json.decodeFromString(RunAwaitingNode.serializer(), "{}") },
            { json.decodeFromString(PipelineNamedShape.serializer(), "{}") },
            { json.decodeFromString(ShapeField.serializer(), "{}") },
            { json.decodeFromString(PipelineShapeRecord.serializer(), "{}") },
            { json.decodeFromString(PipelineRunIteration.serializer(), "{}") },
            { json.decodeFromString(PipelineRecord.serializer(), "{}") },
            { json.decodeFromString(PipelinePermission.serializer(), "{}") },
            { json.decodeFromString(Pipeline.serializer(), "{}") },
            { json.decodeFromString(PipelineRun.serializer(), "{}") },
            { json.decodeFromString(BrokenPipeline.serializer(), "{}") },
            { json.decodeFromString(PipelineAwaitingApproval.serializer(), "{}") },
            { json.decodeFromString(PipelineRunUpdate.serializer(), "{}") },
            { json.decodeFromString(PipelineTriggersChanged.serializer(), "{}") },
            { json.decodeFromString(PipelineRunNodeResult.serializer(), "{}") },
            { json.decodeFromString(PipelineDispatchJob.serializer(), "{}") },
            { json.decodeFromString(PipelineRunJob.serializer(), "{}") },
            { json.decodeFromString(PipelineDelayJob.serializer(), "{}") },
            { json.decodeFromString(PipelineChildRunJob.serializer(), "{}") },
            { json.decodeFromString(PipelineManualRunJob.serializer(), "{}") },
            { json.decodeFromString(PipelineScheduledRunJob.serializer(), "{}") },
            { json.decodeFromString(ExecuteNodeInJob.serializer(), "{}") },
        )

        missingRequiredFields.forEach { decode ->
            assertFailsWith<SerializationException> { decode() }
        }
    }

    @Test
    fun `pipeline state models support minimal and fully specified construction`() {
        val now = java.time.OffsetDateTime.parse("2026-08-18T10:00:00Z")
        val pipelineId = UUID.random()
        val graph = JsonPrimitive("graph")

        val minimalPipeline = Pipeline(id = UUID.NIL, name = "minimal", acceptedInputType = "JSON")
        val fullPipeline = Pipeline(
            id = pipelineId,
            name = "full",
            description = "description",
            acceptedInputType = "sample.Input",
            tags = listOf("sample"),
            triggered = true,
            key = "key",
            api = true,
            public = true,
            schedule = "0 * * * *",
            maxConcurrentRuns = 2,
            maxRunsPerMinute = 3,
            nodes = emptyList(),
            edges = emptyList(),
            groups = emptyList(),
            version = 4,
            gitRepositoryId = UUID.random(),
            gitPath = "pipelines/full.yaml",
            lastSyncError = "none",
            deletedAt = now,
        )
        assertEquals("minimal", minimalPipeline.name)
        assertTrue(fullPipeline.isDeleted)

        val minimalRun = PipelineRun(pipelineId = pipelineId, graphSnapshot = graph)
        val fullRun = PipelineRun(
            id = UUID.random(),
            pipelineId = pipelineId,
            status = PipelineRunStatus.OK,
            eventName = "sample.event",
            graphSnapshot = graph,
            input = JsonPrimitive("input"),
            inputType = "sample.Input",
            nodeOutputs = JsonPrimitive("outputs"),
            awaiting = JsonPrimitive("awaiting"),
            parentRunId = UUID.random(),
            parentNodeId = "parent",
            itemIndex = 2,
            runJobId = UUID.random(),
            principalId = UUID.random(),
            output = JsonPrimitive("output"),
            error = "error",
            version = 5,
            createdAt = now,
            modifiedAt = now.plusMinutes(1),
            deletedAt = now.plusMinutes(2),
        )
        assertEquals(PipelineRunStatus.RUNNING, minimalRun.status)
        assertEquals(PipelineRunStatus.OK, fullRun.status)

        val minimalGraph = PipelineGraph()
        val mixedGraph = PipelineGraph(nodes = emptyList(), groups = emptyList())
        assertTrue(minimalGraph.edges.isEmpty())
        assertTrue(mixedGraph.groups.isEmpty())

        val minimalIteration = PipelineRunIteration(pipelineId, "node", 1, false)
        val fullIteration = PipelineRunIteration(
            pipelineId,
            "node",
            2,
            true,
            results = JsonPrimitive("results"),
            maxConcurrency = 2,
            items = JsonPrimitive("items"),
        )
        assertEquals(0, minimalIteration.maxConcurrency)
        assertEquals(2, fullIteration.maxConcurrency)

        assertEquals(
            JsonPrimitive("partial-results"),
            PipelineRunIteration(pipelineId, "node", 1, false, results = JsonPrimitive("partial-results")).results,
        )
        assertEquals(3, PipelineRunIteration(pipelineId, "node", 1, false, maxConcurrency = 3).maxConcurrency)
        assertEquals(
            JsonPrimitive("partial-items"),
            PipelineRunIteration(pipelineId, "node", 1, false, items = JsonPrimitive("partial-items")).items,
        )

        exerciseEncoding(Pipeline.serializer(), minimalPipeline, fullPipeline)
        exerciseEncoding(PipelineRun.serializer(), minimalRun, fullRun)
        exerciseEncoding(PipelineRunIteration.serializer(), minimalIteration, fullIteration)
    }

    @Test
    fun `required event and operational models deserialize their valid persisted forms`() {
        val id = UUID.random()
        val encodedId = json.encodeToString(UUIDSerializer(), id)

        assertEquals(
            BrokenPipeline(id, "broken", "key", "error"),
            json.decodeFromString(
                BrokenPipeline.serializer(),
                """{"id":$encodedId,"name":"broken","key":"key","error":"error"}""",
            ),
        )
        assertEquals(
            PipelineAwaitingApproval(id, id, "gate", "Approve?"),
            json.decodeFromString(
                PipelineAwaitingApproval.serializer(),
                """{"runId":$encodedId,"pipelineId":$encodedId,"nodeId":"gate","prompt":"Approve?"}""",
            ),
        )
        assertEquals(
            id,
            json.decodeFromString(PipelineRunUpdate.serializer(), """{"runId":$encodedId}""").runId,
        )
        assertEquals(
            id,
            json.decodeFromString(PipelineTriggersChanged.serializer(), """{"pipelineId":$encodedId}""").pipelineId,
        )

        val dispatch = json.decodeFromString(
            PipelineDispatchJob.serializer(),
            """{"eventName":"sample.event","eventPayload":{"id":"one"}}""",
        )
        assertEquals("sample.event", dispatch.eventName)
        val run = json.decodeFromString(
            PipelineRunJob.serializer(),
            """{"pipelineId":$encodedId,"eventName":"sample.event","eventPayload":null}""",
        )
        assertEquals(id, run.pipelineId)
        assertTrue(json.decodeFromString(PipelineSuspendedSweepJob.serializer(), "{}") is PipelineSuspendedSweepJob)
        assertTrue(json.decodeFromString(PipelineRetentionSweepJob.serializer(), "{}") is PipelineRetentionSweepJob)

        val explicitGraph = json.decodeFromString(
            PipelineGraph.serializer(),
            """{"nodes":[],"edges":[],"groups":[]}""",
        )
        assertEquals(PipelineGraph(), explicitGraph)
    }

    @Test
    fun `mixed constructor defaults cover persisted node compatibility paths`() {
        val position = NodePosition(1.0, 2.0)

        val flowNodes = listOf(
            StatusNode("status-a", name = "Named", description = "Desc"),
            StatusNode("status-b", title = "Built", position = position),
            WaitForInputNode("wait-a", name = "Named", description = "Desc"),
            WaitForInputNode("wait-b", prompt = "Input?", position = position),
            ApprovalGateNode("gate-a", name = "Named", description = "Desc"),
            ApprovalGateNode("gate-b", prompt = "Approve?", position = position),
            ThrowNode("throw-a", name = "Named", description = "Desc"),
            ThrowNode("throw-b", message = "Stop", position = position),
        )
        assertEquals(8, flowNodes.size)

        val messageNodes = listOf(
            RenderEmailTemplateNode(
                "render-a", name = "Named", description = "Desc", project = "app", template = "welcome",
            ),
            RenderEmailTemplateNode(
                "render-b", version = "v2", recipientName = "Ada", recipientEmail = "ada@example.com",
                position = position,
            ),
            SendEmailTemplateNode(
                "send-a", name = "Named", description = "Desc", project = "app",
            ),
            SendEmailTemplateNode(
                "send-b", template = "welcome", notificationType = "release", position = position,
            ),
            SendWebhookNode(
                "webhook-a", name = "Named", description = "Desc", urlSecret = "url", signingSecret = "sign",
            ),
            SendWebhookNode(
                "webhook-b", url = "https://example.test", secret = "legacy", position = position,
            ),
            SendSlackNode(
                "slack-a", name = "Named", description = "Desc", webhookSecret = "hook",
            ),
            SendSlackNode(
                "slack-b", webhookUrl = "https://example.test", text = "Hello", position = position,
            ),
        )
        assertEquals(8, messageNodes.size)

        val transformNodes = listOf(
            FlattenNode("flat-a", name = "Named"),
            FlattenNode("flat-b", description = "Desc", position = position),
            DispatchEventNode("dispatch-a", name = "Named", description = "Desc", eventName = "sample.event"),
            DispatchEventNode("dispatch-b", eventName = "sample.event", position = position),
            CastNode("cast-a", name = "Named", description = "Desc"),
            CastNode("cast-b", outputType = "sample.Type", position = position),
            JsonataNode("jsonata-a", name = "Named", description = "Desc", expression = "$.id", outputKind = SlotKind.OBJECT),
            JsonataNode(
                "jsonata-b", expression = "$.id", outputType = "sample.Type",
                outputSchema = JsonPrimitive("schema"), position = position,
            ),
            ToUuidNode("uuid-a", name = "Named"),
            ToUuidNode("uuid-b", description = "Desc", position = position),
            ToStringNode("string-a", name = "Named"),
            ToStringNode("string-b", description = "Desc", position = position),
            GetFormNode("form-a", name = "Named"),
            GetFormNode("form-b", description = "Desc", position = position),
            GetFormSubmissionNode("submission-a", name = "Named"),
            GetFormSubmissionNode("submission-b", description = "Desc", position = position),
            GetIdNode("id-a", name = "Named", description = "Desc"),
            GetIdNode("id-b", position = position, field = "customId"),
        )
        assertEquals(18, transformNodes.size)

        val groupA = NodeGroup("group-a", label = "Build", x = 1.0, y = 2.0, width = 3.0)
        val groupB = NodeGroup(
            "group-b", height = 4.0, collapsed = true, color = "red", nodeIds = listOf("one"),
        )
        val runId = UUID.random()
        val stepA = RunStep(
            "step-a", "First", RunStepKind.STATUS, RunStepStatus.RUNNING, depth = 1, item = "item",
        )
        val stepB = RunStep(
            "step-b", "Second", RunStepKind.HUMAN, RunStepStatus.WAITING,
            runId = runId, type = "gate.approval", channelType = "container",
        )
        assertEquals("Build", groupA.label)
        assertEquals(listOf("one"), groupB.nodeIds)
        assertEquals(1, stepA.depth)
        assertEquals(runId, stepB.runId)
    }

    @Test
    fun `flow nodes decode both stored defaults and explicit graph settings`() {
        val minimalStatus = json.decodeFromString(StatusNode.serializer(), """{"id":"status"}""")
        val fullStatus = json.decodeFromString(
            StatusNode.serializer(),
            """{"id":"status","name":"Named","description":"Desc","title":"Built","position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals("", minimalStatus.title)
        assertEquals("Built", fullStatus.title)
        assertEquals(1.0, fullStatus.position.x)
        exerciseEncoding(StatusNode.serializer(), minimalStatus, fullStatus)

        val minimalWait = json.decodeFromString(WaitForInputNode.serializer(), """{"id":"wait"}""")
        val fullWait = json.decodeFromString(
            WaitForInputNode.serializer(),
            """{"id":"wait","name":"Named","description":"Desc","prompt":"Input?","position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals("", minimalWait.prompt)
        assertEquals("Input?", fullWait.prompt)
        assertTrue(fullWait.willSuspend)
        exerciseEncoding(WaitForInputNode.serializer(), minimalWait, fullWait)

        val minimalGate = json.decodeFromString(ApprovalGateNode.serializer(), """{"id":"gate"}""")
        val fullGate = json.decodeFromString(
            ApprovalGateNode.serializer(),
            """{"id":"gate","name":"Named","description":"Desc","prompt":"Approve?","position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals("", minimalGate.prompt)
        assertEquals("Approve?", fullGate.prompt)
        assertTrue(fullGate.willSuspend)
        exerciseEncoding(ApprovalGateNode.serializer(), minimalGate, fullGate)

        val minimalThrow = json.decodeFromString(ThrowNode.serializer(), """{"id":"throw"}""")
        val fullThrow = json.decodeFromString(
            ThrowNode.serializer(),
            """{"id":"throw","name":"Named","description":"Desc","message":"Stop","position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals("Pipeline failed", minimalThrow.message)
        assertEquals("Stop", fullThrow.message)
        exerciseEncoding(ThrowNode.serializer(), minimalThrow, fullThrow)

        val minimalFlatten = json.decodeFromString(FlattenNode.serializer(), """{"id":"flat"}""")
        val fullFlatten = json.decodeFromString(
            FlattenNode.serializer(),
            """{"id":"flat","name":"Named","description":"Desc","position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals("", minimalFlatten.name)
        assertEquals("Desc", fullFlatten.description)
        exerciseEncoding(FlattenNode.serializer(), minimalFlatten, fullFlatten)

        val minimalDispatch = json.decodeFromString(
            DispatchEventNode.serializer(),
            """{"id":"dispatch","eventName":"sample.event"}""",
        )
        val fullDispatch = json.decodeFromString(
            DispatchEventNode.serializer(),
            """{"id":"dispatch","name":"Named","description":"Desc","eventName":"sample.event","position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals("sample.event", minimalDispatch.eventName)
        assertEquals("Named", fullDispatch.name)
        exerciseEncoding(DispatchEventNode.serializer(), minimalDispatch, fullDispatch)
    }

    @Test
    fun `message nodes decode defaults and complete persisted settings`() {
        val minimalRender = json.decodeFromString(RenderEmailTemplateNode.serializer(), """{"id":"render"}""")
        val fullRender = json.decodeFromString(
            RenderEmailTemplateNode.serializer(),
            """{"id":"render","name":"Named","description":"Desc","project":"app","template":"welcome","version":"v2","recipientName":"Ada","recipientEmail":"ada@example.com","position":{"x":1.0,"y":2.0}}""",
        )
        assertTrue(minimalRender.declaredInputTypes.isEmpty())
        assertEquals(mapOf("payload" to "email:app/welcome"), fullRender.declaredInputTypes)
        assertEquals("v2", fullRender.version)
        assertEquals("Ada", fullRender.recipientName)
        exerciseEncoding(RenderEmailTemplateNode.serializer(), minimalRender, fullRender)

        val minimalSend = json.decodeFromString(SendEmailTemplateNode.serializer(), """{"id":"send"}""")
        val fullSend = json.decodeFromString(
            SendEmailTemplateNode.serializer(),
            """{"id":"send","name":"Named","description":"Desc","project":"app","template":"welcome","notificationType":"release","position":{"x":1.0,"y":2.0}}""",
        )
        assertTrue(minimalSend.declaredInputTypes.isEmpty())
        assertEquals(mapOf("payload" to "email:app/welcome"), fullSend.declaredInputTypes)
        assertEquals("release", fullSend.notificationType)
        exerciseEncoding(SendEmailTemplateNode.serializer(), minimalSend, fullSend)
    }

    @Test
    fun `generic nodes decode omitted defaults and explicit persisted settings`() {
        val minimalCast = json.decodeFromString(CastNode.serializer(), """{"id":"cast"}""")
        val fullCast = json.decodeFromString(
            CastNode.serializer(),
            """{"id":"cast","name":"Named","description":"Desc","outputType":"sample.Type","position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals("", minimalCast.outputType)
        assertEquals("sample.Type", fullCast.outputType)
        exerciseEncoding(CastNode.serializer(), minimalCast, fullCast)

        val minimalJsonata = json.decodeFromString(JsonataNode.serializer(), """{"id":"jsonata","expression":"$.id"}""")
        val fullJsonata = json.decodeFromString(
            JsonataNode.serializer(),
            """{"id":"jsonata","name":"Named","description":"Desc","expression":"$.id","outputKind":"OBJECT","outputType":"sample.Type","outputSchema":{"type":"string"},"position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals(SlotKind.ANY, minimalJsonata.outputKind)
        assertEquals(SlotKind.OBJECT, fullJsonata.declaredOutputKind)
        exerciseEncoding(JsonataNode.serializer(), minimalJsonata, fullJsonata)

        val minimalWebhook = json.decodeFromString(SendWebhookNode.serializer(), """{"id":"webhook"}""")
        assertNull(minimalWebhook.urlSecret)
        val webhook = json.decodeFromString(
            SendWebhookNode.serializer(),
            """{"id":"webhook","name":"Named","description":"Desc","urlSecret":"url","signingSecret":"sign","url":"https://example.test","secret":"legacy","position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals("url", webhook.urlSecret)
        assertEquals("legacy", webhook.secret)
        exerciseEncoding(SendWebhookNode.serializer(), minimalWebhook, webhook)

        val minimalSlack = json.decodeFromString(SendSlackNode.serializer(), """{"id":"slack"}""")
        assertNull(minimalSlack.text)
        val slack = json.decodeFromString(
            SendSlackNode.serializer(),
            """{"id":"slack","name":"Named","description":"Desc","webhookSecret":"hook","webhookUrl":"https://example.test","text":"Hello","position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals("Hello", slack.text)
        exerciseEncoding(SendSlackNode.serializer(), minimalSlack, slack)

        val minimalUuid = json.decodeFromString(ToUuidNode.serializer(), """{"id":"uuid"}""")
        val fullUuid = ToUuidNode("uuid", "Named", "Desc", NodePosition(1.0, 2.0))
        val minimalString = json.decodeFromString(ToStringNode.serializer(), """{"id":"string"}""")
        val fullString = ToStringNode("string", "Named", "Desc", NodePosition(1.0, 2.0))
        val minimalForm = json.decodeFromString(GetFormNode.serializer(), """{"id":"form"}""")
        val fullForm = GetFormNode("form", "Named", "Desc", NodePosition(1.0, 2.0))
        val minimalSubmission = json.decodeFromString(GetFormSubmissionNode.serializer(), """{"id":"submission"}""")
        val fullSubmission = GetFormSubmissionNode("submission", "Named", "Desc", NodePosition(1.0, 2.0))
        assertTrue(listOf(minimalUuid, minimalString, minimalForm, minimalSubmission).all { it.name.isEmpty() })
        exerciseEncoding(ToUuidNode.serializer(), minimalUuid, fullUuid)
        exerciseEncoding(ToStringNode.serializer(), minimalString, fullString)
        exerciseEncoding(GetFormNode.serializer(), minimalForm, fullForm)
        exerciseEncoding(GetFormSubmissionNode.serializer(), minimalSubmission, fullSubmission)

        val minimalId = json.decodeFromString(GetIdNode.serializer(), """{"id":"id"}""")
        val fullId = json.decodeFromString(
            GetIdNode.serializer(),
            """{"id":"id","name":"Named","description":"Desc","position":{"x":1.0,"y":2.0},"field":"customId"}""",
        )
        assertEquals("customId", fullId.field)
        assertEquals("id", minimalId.field)
        exerciseEncoding(GetIdNode.serializer(), minimalId, fullId)

        val minimalJob = json.decodeFromString(
            ExecuteJobNode.serializer(),
            """{"id":"job","jobName":"sample"}""",
        )
        assertFalse(minimalJob.awaitCompletion)
        val job = json.decodeFromString(
            ExecuteJobNode.serializer(),
            """{"id":"job","name":"Named","description":"Desc","jobName":"sample","awaitCompletion":true,"awaitTimeoutSeconds":30,"position":{"x":1.0,"y":2.0}}""",
        )
        assertTrue(job.awaitCompletion)
        assertEquals(30L, job.awaitTimeoutSeconds)
        exerciseEncoding(ExecuteJobNode.serializer(), minimalJob, job)

        val minimalRun = json.decodeFromString(RunPipelineNode.serializer(), """{"id":"run"}""")
        val minimalEach = json.decodeFromString(ForEach.serializer(), """{"id":"each"}""")
        val minimalUntil = json.decodeFromString(WaitUntilNode.serializer(), """{"id":"wait"}""")
        val minimalSwitch = json.decodeFromString(SwitchNode.serializer(), """{"id":"switch"}""")
        assertNull(minimalRun.pipelineId)
        assertNull(minimalEach.pipelineId)
        assertEquals(1, minimalEach.maxConcurrency)
        assertEquals("", minimalUntil.until)
        assertEquals("default", minimalSwitch.defaultLabel)
        exerciseEncoding(
            RunPipelineNode.serializer(), minimalRun,
            RunPipelineNode("run", "Named", "Desc", UUID.random(), NodePosition(1.0, 2.0)),
        )
        exerciseEncoding(
            ForEach.serializer(), minimalEach,
            ForEach(
                "each", "Named", "Desc", UUID.random(), "items", 4, true,
                NodePosition(1.0, 2.0),
            ),
        )
        exerciseEncoding(
            WaitUntilNode.serializer(), minimalUntil,
            WaitUntilNode("wait", "Named", "Desc", "payload.at", "2026-08-18T10:00:00Z", NodePosition(1.0, 2.0)),
        )
        exerciseEncoding(
            SwitchNode.serializer(), minimalSwitch,
            SwitchNode(
                "switch", "Named", "Desc", listOf(SwitchCase("yes", "true")), "otherwise",
                NodePosition(1.0, 2.0),
            ),
        )
    }

    @Test
    fun `uuid-backed nodes decode required ids with optional settings present and absent`() {
        val id = UUID.random()
        val encodedId = json.encodeToString(UUIDSerializer(), id)
        val minimalScript = json.decodeFromString(
            ExecuteScriptNode.serializer(),
            """{"id":"script","scriptId":$encodedId}""",
        )
        val fullScript = json.decodeFromString(
            ExecuteScriptNode.serializer(),
            """{"id":"script","name":"Named","description":"Desc","scriptId":$encodedId,"dryRunEnabled":true,"position":{"x":1.0,"y":2.0}}""",
        )
        assertEquals(id, minimalScript.scriptId)
        assertFalse(minimalScript.dryRunEnabled)
        assertTrue(fullScript.dryRunEnabled)
        exerciseEncoding(ExecuteScriptNode.serializer(), minimalScript, fullScript)

        val runPipeline = RunPipelineNode("run", name = "Named", description = "Desc", pipelineId = id)
        val each = ForEach(
            "each", name = "Named", description = "Desc", pipelineId = id,
            itemsField = "items", maxConcurrency = 4, continueOnError = true,
        )
        assertEquals(
            id,
            json.decodeFromString(
                RunPipelineNode.serializer(),
                json.encodeToString(RunPipelineNode.serializer(), runPipeline),
            ).pipelineId,
        )
        val decodedEach = json.decodeFromString(ForEach.serializer(), json.encodeToString(ForEach.serializer(), each))
        assertEquals(id, decodedEach.pipelineId)
        assertEquals(4, decodedEach.maxConcurrency)
        assertTrue(decodedEach.continueOnError)
    }

    @Test
    fun `step models decode minimal and complete persisted forms`() {
        val minimalGroup = json.decodeFromString(NodeGroup.serializer(), """{"id":"group"}""")
        val fullGroup = json.decodeFromString(
            NodeGroup.serializer(),
            """{"id":"group","label":"Build","x":1.0,"y":2.0,"width":3.0,"height":4.0,"collapsed":true,"color":"red","nodeIds":["one"]}""",
        )
        assertEquals(240.0, minimalGroup.width)
        assertEquals(listOf("one"), fullGroup.nodeIds)
        assertTrue(fullGroup.collapsed)
        exerciseEncoding(NodeGroup.serializer(), minimalGroup, fullGroup)

        val minimalStep = json.decodeFromString(
            RunStep.serializer(),
            """{"nodeId":"status","title":"Built","kind":"STATUS","status":"PENDING"}""",
        )
        val runId = UUID.random()
        val fullStep = RunStep(
            "gate", "Approve", RunStepKind.HUMAN, RunStepStatus.WAITING,
            2, "api", runId, "gate.approval", "container",
        )
        val decodedStep = json.decodeFromString(RunStep.serializer(), json.encodeToString(RunStep.serializer(), fullStep))
        assertEquals(0, minimalStep.depth)
        assertEquals(fullStep, decodedStep)
        exerciseEncoding(RunStep.serializer(), minimalStep, fullStep)

        val minimalAwaiting = json.decodeFromString(
            RunAwaitingNode.serializer(),
            """{"nodeId":"gate","type":"gate.approval","name":"Approve"}""",
        )
        val fullAwaiting = RunAwaitingNode("gate", "gate.approval", "Approve", runId)
        assertNull(minimalAwaiting.runId)
        assertEquals(
            fullAwaiting,
            json.decodeFromString(RunAwaitingNode.serializer(), json.encodeToString(RunAwaitingNode.serializer(), fullAwaiting)),
        )
        exerciseEncoding(RunAwaitingNode.serializer(), minimalAwaiting, fullAwaiting)
    }

    @Test
    fun `shape models decode omitted and explicit collections and timestamps`() {
        val minimal = json.decodeFromString(PipelineNamedShape.serializer(), """{"name":"entity"}""")
        val full = PipelineNamedShape("entity", listOf(ShapeField("id", "String")))
        assertTrue(minimal.fields.isEmpty())
        val decodedFull = json.decodeFromString(
            PipelineNamedShape.serializer(),
            json.encodeToString(PipelineNamedShape.serializer(), full),
        )
        assertEquals("entity", decodedFull.name)
        assertEquals("id", decodedFull.fields.single().name)
        assertEquals("String", decodedFull.fields.single().type)
        exerciseEncoding(PipelineNamedShape.serializer(), minimal, full)

        val fields = JsonArray(listOf(JsonPrimitive("field")))
        val defaults = PipelineShapeRecord("shape", fields)
        val complete = PipelineShapeRecord(
            "shape",
            fields,
            java.time.OffsetDateTime.parse("2026-08-18T10:00:00Z"),
            java.time.OffsetDateTime.parse("2026-08-18T11:00:00Z"),
        )
        val decodedDefaults = json.decodeFromString(
            PipelineShapeRecord.serializer(),
            """{"name":"shape","fields":["field"]}""",
        )
        val decodedComplete = json.decodeFromString(
            PipelineShapeRecord.serializer(),
            json.encodeToString(PipelineShapeRecord.serializer(), complete),
        )
        assertEquals(defaults.name, decodedDefaults.name)
        assertEquals(complete, decodedComplete)
        assertEquals(complete, complete)
        assertFalse(complete.equals(Any()))
        assertNotEquals(complete, complete.copy(name = "other"))
        assertNotEquals(complete, complete.copy(fields = JsonArray(emptyList())))
        assertNotEquals(complete, complete.copy(createdAt = complete.createdAt.plusMinutes(1)))
        assertNotEquals(complete, complete.copy(modifiedAt = complete.modifiedAt.plusMinutes(1)))
        assertEquals(complete, complete.copy())
        assertFalse(decodedComplete.createdAt == decodedComplete.modifiedAt)
        assertEquals(
            complete.createdAt,
            PipelineShapeRecord("shape", fields, createdAt = complete.createdAt).createdAt,
        )
        assertEquals(
            complete.modifiedAt,
            PipelineShapeRecord("shape", fields, modifiedAt = complete.modifiedAt).modifiedAt,
        )
        exerciseEncoding(PipelineShapeRecord.serializer(), decodedDefaults, complete)
    }
}
