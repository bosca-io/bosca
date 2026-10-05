package bosca.pipelines.service

import bosca.pipelines.builtin.ApprovalGateNode
import bosca.pipelines.builtin.ForEach
import bosca.pipelines.builtin.RunPipelineNode
import bosca.pipelines.builtin.StatusNode
import bosca.pipelines.builtin.WaitForInputNode
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.model.NodeExecutionRecord
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.model.RunStepStatus
import bosca.pipelines.repository.NodeExecutionRepository
import bosca.pipelines.repository.PipelineRunIterationRepository
import bosca.pipelines.repository.PipelineRunLogRepository
import bosca.pipelines.repository.PipelineRunRepository
import bosca.pipelines.repository.RollbackRepository
import bosca.pubsub.PubSubService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineRunStepProjectionTest {

    private val runRepository = mockk<PipelineRunRepository>(relaxed = true)
    private val nodeExecutionRepository = mockk<NodeExecutionRepository>(relaxed = true)
    private val pipelineService = mockk<PipelineService>(relaxed = true)
    private val service = PipelineRunServiceImpl(
        runRepository = runRepository,
        runLogRepository = mockk<PipelineRunLogRepository>(relaxed = true),
        resultStore = mockk<PipelineRunResultStore>(relaxed = true),
        nodeExecutionRepository = nodeExecutionRepository,
        iterationRepository = mockk<PipelineRunIterationRepository>(relaxed = true),
        rollbackRepository = mockk<RollbackRepository>(relaxed = true),
        pipelineService = pipelineService,
        executor = mockk<PipelineExecutor>(relaxed = true),
        securityService = mockk<SecurityService>(relaxed = true),
        config = mockk<PipelinesRuntimeConfiguration>(relaxed = true),
        pubSub = mockk<PubSubService>(relaxed = true),
    )

    @Test
    fun `steps derive progress channels and labelled fan-out children from durable state`() = runTest {
        val parent = run(
            status = PipelineRunStatus.SUSPENDED,
            graph = graph(
                nodes = listOf(
                    node("select", "artifact.select", "artifactType" to "container"),
                    node("built", "status", "title" to "Built"),
                    node("gate", "gate.approval", "prompt" to "Promote?"),
                    node("input", "waitForInput", "name" to "Release notes"),
                    node("skipped", "status", "title" to "Skipped branch"),
                    node("each", "forEach"),
                    buildJsonObject { put("type", "status") },
                ),
                edges = listOf(
                    "select" to "built",
                    "built" to "gate",
                    "gate" to "input",
                    "select" to "skipped",
                    "input" to "each",
                    "missing" to "built",
                ),
            ),
            outputs = buildJsonObject {
                put("select", "image")
                put("built", "ok")
            },
            awaiting = awaits("gate"),
        )
        val firstChild = run(
            status = PipelineRunStatus.RUNNING,
            graph = graph(listOf(node("child-one", "status", "name" to "Build child"))),
            input = buildJsonObject { put("tag", "api-v1") },
            itemIndex = 0,
        )
        val secondChild = run(
            status = PipelineRunStatus.OK,
            graph = graph(listOf(node("child-two", "status", "title" to "Child complete"))),
            input = buildJsonObject { put("name", " ") },
            itemIndex = 1,
        )
        coEvery { runRepository.getById(parent.id) } returns parent
        coEvery { runRepository.listByParentAndNode(parent.id, "each") } returns listOf(firstChild, secondChild)
        coEvery { nodeExecutionRepository.listForRun(parent.id) } returns listOf(record("skipped", NodeExecutionStatus.SKIPPED))
        coEvery { nodeExecutionRepository.listForRun(firstChild.id) } returns emptyList()
        coEvery { nodeExecutionRepository.listForRun(secondChild.id) } returns listOf(record("child-two", NodeExecutionStatus.OK))

        val rows = service.steps(parent.id)

        assertEquals(listOf("built", "gate", "input", "child-one", "child-two"), rows.map { it.nodeId })
        assertEquals(RunStepStatus.DONE, rows[0].status)
        assertEquals("container", rows[0].channelType)
        assertEquals(RunStepStatus.WAITING, rows[1].status)
        assertEquals(RunStepStatus.PENDING, rows[2].status)
        assertEquals(RunStepStatus.RUNNING, rows[3].status)
        assertEquals("api-v1", rows[3].item)
        assertEquals(firstChild.id, rows[3].runId)
        assertEquals(RunStepStatus.DONE, rows[4].status)
        assertEquals("item 2", rows[4].item)
        assertEquals(1, rows[4].depth)
    }

    @Test
    fun `steps handle missing malformed failed cyclic and dead-channel runs`() = runTest {
        val missingId = UUID.random()
        coEvery { runRepository.getById(missingId) } returns null
        assertTrue(service.steps(missingId).isEmpty())

        val malformed = run(graph = JsonPrimitive("not-a-graph"))
        coEvery { runRepository.getById(malformed.id) } returns malformed
        assertTrue(service.steps(malformed.id).isEmpty())

        val dead = run(
            graph = graph(
                listOf(node("select", "artifact.select", "artifactType" to "jar"), node("publish", "status")),
                listOf("select" to "publish"),
            ),
            outputs = buildJsonObject { put("select", JsonNull) },
        )
        coEvery { runRepository.getById(dead.id) } returns dead
        assertTrue(service.steps(dead.id).isEmpty())

        val failed = run(
            status = PipelineRunStatus.FAILED,
            graph = graph(
                listOf(
                    node("first", "status", "title" to "", "name" to "First fallback"),
                    node("gate", "gate.approval"),
                    node("last", "status"),
                ),
                listOf("first" to "gate", "gate" to "first", "gate" to "last"),
            ),
            awaiting = awaits("gate"),
        )
        coEvery { runRepository.getById(failed.id) } returns failed

        val rows = service.steps(failed.id)

        assertEquals(listOf("first", "gate", "last"), rows.map { it.nodeId })
        assertEquals("First fallback", rows[0].title)
        assertEquals(RunStepStatus.FAILED, rows[0].status)
        assertEquals(RunStepStatus.PENDING, rows[1].status)
        assertEquals("last", rows[2].title)
    }

    @Test
    fun `a node reached from two artifact selects is channel agnostic`() = runTest {
        val current = run(
            graph = graph(
                listOf(
                    node("left", "artifact.select", "artifactType" to "jar"),
                    node("right", "artifact.select", "artifactType" to "image"),
                    node("join", "status", "title" to "Joined"),
                ),
                listOf("left" to "join", "right" to "join", "left" to "join"),
            ),
        )
        coEvery { runRepository.getById(current.id) } returns current

        val row = service.steps(current.id).single()

        assertNull(row.channelType)
        assertEquals(RunStepStatus.RUNNING, row.status)
    }

    @Test
    fun `an unreached child container pre-shows the child pipeline plan`() = runTest {
        val childId = UUID.random()
        val parent = run(
            graph = graph(
                listOf(node("child", "runPipeline", "pipelineId" to childId.toString())),
            ),
        )
        val child = Pipeline(
            id = childId,
            name = "child",
            acceptedInputType = "JSON",
            nodes = listOf(StatusNode("planned", title = "Planned child step")),
        )
        coEvery { runRepository.getById(parent.id) } returns parent
        coEvery { runRepository.listByParentAndNode(parent.id, "child") } returns emptyList()
        coEvery { pipelineService.get(childId) } returns child
        coEvery { pipelineService.graphAsJsonElement(child) } returns graph(emptyList())

        val row = service.steps(parent.id).single()

        assertEquals("planned", row.nodeId)
        assertEquals("Planned child step", row.title)
        assertEquals(1, row.depth)
        assertEquals(RunStepStatus.PENDING, row.status)
    }

    @Test
    fun `delete reports whether the durable run was soft deleted`() = runTest {
        val deleted = UUID.random()
        val missing = UUID.random()
        coEvery { runRepository.softDeleteById(deleted) } returns 1
        coEvery { runRepository.softDeleteById(missing) } returns 0

        assertTrue(service.delete(deleted))
        assertEquals(false, service.delete(missing))
    }

    @Test
    fun `trigger plans include nested authored steps while filtering ineligible pipelines and cycles`() = runTest {
        val ids = List(8) { UUID.random() }
        val tail = (1..7).map { index ->
            Pipeline(
                id = ids[index],
                name = "child-$index",
                acceptedInputType = "event",
                nodes = listOf(
                    StatusNode("status-$index", title = "Child $index"),
                    RunPipelineNode("next-$index", pipelineId = ids.getOrNull(index + 1) ?: ids[0]),
                ),
            )
        }
        val root = Pipeline(
            id = ids[0],
            name = "root",
            acceptedInputType = "event",
            triggered = true,
            nodes = listOf(
                StatusNode("status", name = "", title = ""),
                ApprovalGateNode("gate", name = "Named gate", prompt = ""),
                WaitForInputNode("wait", prompt = "Supply value"),
                ForEach("each", pipelineId = ids[1]),
                RunPipelineNode("child", pipelineId = ids[2]),
            ),
        )
        val wrongType = root.copy(id = UUID.random(), acceptedInputType = "other")
        val disabled = root.copy(id = UUID.random(), triggered = false)
        val deleted = root.copy(id = UUID.random(), deletedAt = java.time.OffsetDateTime.now())
        coEvery { pipelineService.getAll() } returns listOf(root, wrongType, disabled, deleted)
        coEvery { pipelineService.get(root.id) } returns root
        tail.forEach { child -> coEvery { pipelineService.get(child.id) } returns child }
        coEvery { pipelineService.graphAsJsonElement(any()) } returns graph(emptyList())

        val rows = service.stepsForTrigger("event")

        assertEquals(RunStepStatus.PENDING, rows.first().status)
        assertEquals("status", rows.first().title)
        assertEquals("Named gate", rows[1].title)
        assertEquals("Supply value", rows[2].title)
        assertTrue(rows.any { it.nodeId == "status-1" })
        assertTrue(rows.size < 30, "cycles and excessive nesting must be bounded")
    }

    @Test
    fun `awaiting nodes drill through active children and retain direct waits`() = runTest {
        val parent = run(
            status = PipelineRunStatus.SUSPENDED,
            graph = graph(
                listOf(
                    node("each", "forEach", "name" to "Each item"),
                    node("direct", "gate.approval", "title" to "Direct approval"),
                    node("empty", "waitForInput", "name" to "", "title" to "", "prompt" to ""),
                ),
            ),
            awaiting = awaits("each", "direct", "empty", "unknown"),
        )
        val activeChild = run(
            status = PipelineRunStatus.SUSPENDED,
            graph = graph(listOf(node("child-wait", "waitForInput", "prompt" to "Build result"))),
            awaiting = awaits("child-wait"),
            input = buildJsonObject { put("key", "api") },
            itemIndex = 0,
        )
        val completedChild = run(
            status = PipelineRunStatus.OK,
            graph = graph(listOf(node("ignored", "waitForInput"))),
            awaiting = awaits("ignored"),
            itemIndex = 1,
        )
        coEvery { runRepository.getById(parent.id) } returns parent
        coEvery { runRepository.listByParentAndNode(parent.id, "each") } returns listOf(activeChild, completedChild)

        val rows = service.awaitingNodes(parent.id)

        assertEquals(listOf("child-wait", "direct", "empty", "unknown"), rows.map { it.nodeId })
        assertEquals("Build result (api)", rows[0].name)
        assertEquals(activeChild.id, rows[0].runId)
        assertEquals("Direct approval", rows[1].name)
        assertEquals("empty", rows[2].name)
        assertEquals("", rows[3].type)
        assertEquals(parent.id, rows[3].runId)
    }

    @Test
    fun `awaiting nodes return empty for missing runs and malformed snapshots`() = runTest {
        val missing = UUID.random()
        coEvery { runRepository.getById(missing) } returns null
        assertTrue(service.awaitingNodes(missing).isEmpty())

        val malformed = run(graph = JsonPrimitive("bad"), awaiting = JsonPrimitive("bad"))
        coEvery { runRepository.getById(malformed.id) } returns malformed
        assertTrue(service.awaitingNodes(malformed.id).isEmpty())
    }

    @Test
    fun `steps cover terminal human states and every fan-out item label fallback`() = runTest {
        val parent = run(
            status = PipelineRunStatus.CANCELLED,
            graph = graph(
                nodes = listOf(
                    node("status-done", "status", "title" to "Done"),
                    node("gate-done", "gate.approval", "prompt" to "Already approved"),
                    node("wait-failed", "waitForInput", "prompt" to "Stale wait"),
                    node("status-pending", "status", "name" to "Later"),
                    node("gate-pending", "gate.approval"),
                    node("single", "forEach"),
                    node("multi", "runPipeline"),
                    node("unreached", "runPipeline"),
                ),
            ),
            outputs = buildJsonObject {
                put("status-done", true)
                put("gate-done", true)
            },
            awaiting = awaits("wait-failed"),
        )
        val single = run(
            graph = graph(listOf(node("single-step", "status"))),
            input = buildJsonObject { put("title", "only") },
            itemIndex = 0,
        )
        val unindexed = run(
            graph = graph(listOf(node("unindexed-step", "status"))),
            input = JsonPrimitive("raw"),
            itemIndex = null,
        )
        val titled = run(
            graph = graph(listOf(node("titled-step", "status"))),
            input = buildJsonObject { put("title", "release") },
            itemIndex = 1,
        )
        val scalar = run(
            graph = graph(listOf(node("scalar-step", "status"))),
            input = JsonPrimitive("raw"),
            itemIndex = 2,
        )
        coEvery { runRepository.getById(parent.id) } returns parent
        coEvery { runRepository.listByParentAndNode(parent.id, "single") } returns listOf(single)
        coEvery { runRepository.listByParentAndNode(parent.id, "multi") } returns listOf(unindexed, titled, scalar)
        coEvery { runRepository.listByParentAndNode(parent.id, "unreached") } returns emptyList()
        listOf(parent, single, unindexed, titled, scalar).forEach { current ->
            coEvery { nodeExecutionRepository.listForRun(current.id) } returns emptyList()
        }

        val rows = service.steps(parent.id)

        assertEquals(RunStepStatus.DONE, rows.single { it.nodeId == "status-done" }.status)
        assertEquals(RunStepStatus.DONE, rows.single { it.nodeId == "gate-done" }.status)
        assertEquals(RunStepStatus.FAILED, rows.single { it.nodeId == "wait-failed" }.status)
        assertEquals(RunStepStatus.PENDING, rows.single { it.nodeId == "status-pending" }.status)
        assertEquals(RunStepStatus.PENDING, rows.single { it.nodeId == "gate-pending" }.status)
        assertNull(rows.single { it.nodeId == "single-step" }.item)
        assertNull(rows.single { it.nodeId == "unindexed-step" }.item)
        assertEquals("release", rows.single { it.nodeId == "titled-step" }.item)
        assertEquals("item 3", rows.single { it.nodeId == "scalar-step" }.item)
    }

    @Test
    fun `steps tolerate malformed graph members and traverse cyclic artifact channels`() = runTest {
        val current = run(
            graph = buildJsonObject {
                put("nodes", JsonArray(listOf(
                    JsonPrimitive("not-a-node"),
                    buildJsonObject { put("type", "artifact.select") },
                    node("untyped", "artifact.select"),
                    node("select", "artifact.select", "artifactType" to "image"),
                    node("build", "status", "title" to "Build"),
                    buildJsonObject { put("id", buildJsonObject { put("bad", true) }); put("type", "status") },
                )))
                put("edges", JsonArray(listOf(
                    JsonPrimitive("not-an-edge"),
                    buildJsonObject { put("target", "build") },
                    buildJsonObject { put("source", "select") },
                    buildJsonObject { put("source", buildJsonObject { }); put("target", "build") },
                    buildJsonObject { put("source", "missing"); put("target", "build") },
                    buildJsonObject { put("source", "select"); put("target", "build") },
                    buildJsonObject { put("source", "build"); put("target", "build") },
                )))
            },
        )
        coEvery { runRepository.getById(current.id) } returns current
        coEvery { nodeExecutionRepository.listForRun(current.id) } returns emptyList()

        val rows = service.steps(current.id)

        assertEquals(listOf("build"), rows.map { it.nodeId })
        assertEquals("image", rows.single().channelType)

        val noEdges = run(
            graph = buildJsonObject {
                put("nodes", JsonArray(listOf(node("plain", "status"))))
                put("edges", "not-an-array")
            },
        )
        coEvery { runRepository.getById(noEdges.id) } returns noEdges
        coEvery { nodeExecutionRepository.listForRun(noEdges.id) } returns emptyList()
        assertEquals("plain", service.steps(noEdges.id).single().nodeId)

        val noNodes = run(graph = buildJsonObject { put("nodes", "not-an-array") })
        coEvery { runRepository.getById(noNodes.id) } returns noNodes
        coEvery { nodeExecutionRepository.listForRun(noNodes.id) } returns emptyList()
        assertTrue(service.steps(noNodes.id).isEmpty())
    }

    @Test
    fun `steps treat a malformed awaiting checkpoint as having no parked nodes`() = runTest {
        val current = run(
            status = PipelineRunStatus.SUSPENDED,
            graph = graph(
                listOf(
                    node("started", "status", "title" to "Started"),
                    node("approval", "gate.approval", "prompt" to "Approve?"),
                ),
                listOf("started" to "approval"),
            ),
            awaiting = JsonPrimitive("not-an-await-list"),
        )
        coEvery { runRepository.getById(current.id) } returns current
        coEvery { nodeExecutionRepository.listForRun(current.id) } returns emptyList()

        val rows = service.steps(current.id)

        assertEquals(RunStepStatus.RUNNING, rows.single { it.nodeId == "started" }.status)
        assertEquals(RunStepStatus.PENDING, rows.single { it.nodeId == "approval" }.status)
    }

    @Test
    fun `awaiting containers fall back to their own row when active children have no nested waits`() = runTest {
        val parent = run(
            status = PipelineRunStatus.SUSPENDED,
            graph = graph(listOf(node("child", "runPipeline", "name" to "Child run"))),
            awaiting = JsonArray(listOf(
                JsonPrimitive("bad"),
                buildJsonObject { put("missing", "nodeId") },
                buildJsonObject { put("nodeId", buildJsonObject { }) },
                buildJsonObject { put("nodeId", "child") },
            )),
        )
        val active = run(
            status = PipelineRunStatus.SUSPENDED,
            graph = graph(listOf(node("idle", "status"))),
            awaiting = JsonArray(emptyList()),
            itemIndex = 0,
        )
        coEvery { runRepository.getById(parent.id) } returns parent
        coEvery { runRepository.listByParentAndNode(parent.id, "child") } returns listOf(active)

        val row = service.awaitingNodes(parent.id).single()

        assertEquals("child", row.nodeId)
        assertEquals("Child run", row.name)
        assertEquals(parent.id, row.runId)
    }

    @Test
    fun `trigger plans cover missing nullable and channel-attributed child declarations`() = runTest {
        val missingId = UUID.random()
        val root = Pipeline(
            id = UUID.random(),
            name = "root",
            acceptedInputType = "event",
            triggered = true,
            nodes = listOf(
                StatusNode("named", name = "Named status"),
                ApprovalGateNode("gate"),
                WaitForInputNode("wait"),
                ForEach("empty-each", pipelineId = null),
                RunPipelineNode("empty-run", pipelineId = null),
                RunPipelineNode("missing-run", pipelineId = missingId),
            ),
        )
        coEvery { pipelineService.getAll() } returns listOf(root)
        coEvery { pipelineService.get(root.id) } returns root
        coEvery { pipelineService.get(missingId) } returns null
        coEvery { pipelineService.graphAsJsonElement(root) } returns graph(
            nodes = listOf(
                node("select", "artifact.select", "artifactType" to "package"),
                node("named", "status"),
            ),
            edges = listOf("select" to "named"),
        )

        val rows = service.stepsForTrigger("event")

        assertEquals(listOf("named", "gate", "wait"), rows.map { it.nodeId })
        assertEquals("Named status", rows[0].title)
        assertEquals("package", rows[0].channelType)
        assertEquals("gate", rows[1].title)
        assertEquals("wait", rows[2].title)
    }

    @Test
    fun `trigger plans prefer authored human prompts then names before node ids`() = runTest {
        val root = Pipeline(
            id = UUID.random(),
            name = "root",
            acceptedInputType = "event",
            triggered = true,
            nodes = listOf(
                ApprovalGateNode("prompted-gate", name = "Fallback gate", prompt = "Approve release?"),
                WaitForInputNode("named-wait", name = "Supply artifact", prompt = ""),
            ),
        )
        coEvery { pipelineService.getAll() } returns listOf(root)
        coEvery { pipelineService.get(root.id) } returns root
        coEvery { pipelineService.graphAsJsonElement(root) } returns graph(emptyList())

        val rows = service.stepsForTrigger("event")

        assertEquals(listOf("Approve release?", "Supply artifact"), rows.map { it.title })
    }

    @Test
    fun `steps exhaust malformed checkpoint and terminal versus active projection arms`() = runTest {
        val malformedState = run(
            graph = buildJsonObject {
                put("nodes", JsonArray(listOf(
                    buildJsonObject { put("id", "unknown") },
                    buildJsonObject { put("id", "wrong-type"); put("type", buildJsonObject { }) },
                    buildJsonObject { put("id", "fallback"); put("type", "status"); put("title", buildJsonObject { }); put("name", " ") },
                    node("later", "status", "title" to "Later"),
                    node("human", "waitForInput", "prompt" to " ", "name" to "Named wait"),
                    node("done-container", "forEach"),
                )))
                put("edges", JsonArray(emptyList()))
            },
            outputs = JsonPrimitive("not-an-object"),
            awaiting = JsonArray(listOf(
                JsonPrimitive("bad"),
                buildJsonObject { put("nodeId", buildJsonObject { }) },
                buildJsonObject { put("other", "missing") },
            )),
        )
        coEvery { runRepository.getById(malformedState.id) } returns malformedState
        coEvery { nodeExecutionRepository.listForRun(malformedState.id) } returns emptyList()
        coEvery { runRepository.listByParentAndNode(malformedState.id, "done-container") } returns emptyList()

        val activeRows = service.steps(malformedState.id)

        assertEquals(listOf("fallback", "later", "human"), activeRows.map { it.nodeId })
        assertEquals("fallback", activeRows[0].title)
        assertEquals(RunStepStatus.RUNNING, activeRows[0].status)
        assertEquals(RunStepStatus.PENDING, activeRows[1].status)
        assertEquals("Named wait", activeRows[2].title)

        val terminal = run(
            status = PipelineRunStatus.OK,
            graph = graph(listOf(node("status", "status"), node("gate", "gate.approval"))),
        )
        coEvery { runRepository.getById(terminal.id) } returns terminal
        coEvery { nodeExecutionRepository.listForRun(terminal.id) } returns emptyList()
        assertTrue(service.steps(terminal.id).all { it.status == RunStepStatus.PENDING })

        val completedContainer = run(
            graph = graph(listOf(node("container", "runPipeline"))),
            outputs = buildJsonObject { put("container", JsonNull) },
        )
        coEvery { runRepository.getById(completedContainer.id) } returns completedContainer
        coEvery { nodeExecutionRepository.listForRun(completedContainer.id) } returns emptyList()
        coEvery { runRepository.listByParentAndNode(completedContainer.id, "container") } returns emptyList()
        assertTrue(service.steps(completedContainer.id).isEmpty())
    }

    @Test
    fun `step recursion stops at the durable projection depth limit`() = runTest {
        val chain = (0..10).map { index ->
            run(graph = graph(listOf(node("child-$index", "runPipeline"))))
        }
        coEvery { runRepository.getById(chain.first().id) } returns chain.first()
        chain.forEachIndexed { index, current ->
            coEvery { nodeExecutionRepository.listForRun(current.id) } returns emptyList()
            coEvery { runRepository.listByParentAndNode(current.id, "child-$index") } returns
                chain.getOrNull(index + 1)?.let(::listOf).orEmpty()
        }

        assertTrue(service.steps(chain.first().id).isEmpty())
    }

    @Test
    fun `step and awaiting projections remain correct across suspending repository reads`() = runTest {
        val current = run(
            status = PipelineRunStatus.SUSPENDED,
            graph = graph(listOf(node("status", "status"), node("wait", "waitForInput"))),
            awaiting = awaits("wait"),
        )
        coEvery { runRepository.getById(current.id) } coAnswers { yield(); current }
        coEvery { nodeExecutionRepository.listForRun(current.id) } coAnswers { yield(); emptyList() }

        assertEquals(listOf("status", "wait"), service.steps(current.id).map { it.nodeId })
        assertEquals(listOf("wait"), service.awaitingNodes(current.id).map { it.nodeId })
    }

    @Test
    fun `awaiting projection stops at its durable recursion depth limit`() = runTest {
        val chain = (0..11).map { index ->
            run(
                status = PipelineRunStatus.SUSPENDED,
                graph = graph(listOf(node("child-$index", "runPipeline"))),
                awaiting = awaits("child-$index"),
                itemIndex = index,
            )
        }
        coEvery { runRepository.getById(chain.first().id) } returns chain.first()
        chain.forEachIndexed { index, current ->
            coEvery { runRepository.listByParentAndNode(current.id, "child-$index") } returns
                chain.getOrNull(index + 1)?.let(::listOf).orEmpty()
        }

        assertEquals(listOf("child-5"), service.awaitingNodes(chain.first().id).map { it.nodeId })
    }

    @Test
    fun `projections ignore JsonNull graph keys and invalid target-only edges`() = runTest {
        val current = run(
            status = PipelineRunStatus.SUSPENDED,
            graph = buildJsonObject {
                put("nodes", JsonArray(listOf(
                    buildJsonObject { put("id", JsonNull); put("type", "status") },
                    buildJsonObject { put("id", "null-type"); put("type", JsonNull) },
                    buildJsonObject { put("id", "status"); put("type", "status"); put("title", JsonNull); put("name", JsonNull) },
                    buildJsonObject {
                        put("id", "wait")
                        put("type", "waitForInput")
                        put("prompt", JsonNull)
                        put("name", JsonNull)
                        put("title", JsonNull)
                    },
                    buildJsonObject { put("id", JsonNull); put("type", "artifact.select"); put("artifactType", "image") },
                )))
                put("edges", JsonArray(listOf(
                    buildJsonObject { put("source", JsonNull); put("target", "status") },
                    buildJsonObject { put("source", "status"); put("target", JsonNull) },
                    buildJsonObject { put("source", "status"); put("target", "missing-target") },
                )))
            },
            awaiting = JsonArray(listOf(
                buildJsonObject { put("nodeId", JsonNull) },
                buildJsonObject { put("nodeId", "wait") },
            )),
        )
        coEvery { runRepository.getById(current.id) } returns current
        coEvery { nodeExecutionRepository.listForRun(current.id) } returns emptyList()

        assertEquals(listOf("status", "wait"), service.steps(current.id).map { it.nodeId })
        assertEquals(listOf("wait"), service.awaitingNodes(current.id).map { it.nodeId })
    }

    private fun run(
        status: PipelineRunStatus = PipelineRunStatus.RUNNING,
        graph: JsonElement,
        outputs: JsonElement = JsonObject(emptyMap()),
        awaiting: JsonElement = JsonArray(emptyList()),
        input: JsonElement? = null,
        itemIndex: Int? = null,
    ) = PipelineRun(
        id = UUID.random(),
        pipelineId = UUID.random(),
        status = status,
        graphSnapshot = graph,
        nodeOutputs = outputs,
        awaiting = awaiting,
        input = input,
        itemIndex = itemIndex,
    )

    private fun node(id: String, type: String, vararg strings: Pair<String, String>): JsonObject =
        buildJsonObject {
            put("id", id)
            put("type", type)
            strings.forEach { (key, value) -> put(key, value) }
        }

    private fun graph(
        nodes: List<JsonObject>,
        edges: List<Pair<String, String>> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("nodes", JsonArray(nodes))
        put("edges", JsonArray(edges.map { (source, target) ->
            buildJsonObject {
                put("source", source)
                put("target", target)
            }
        }))
    }

    private fun awaits(vararg nodeIds: String): JsonArray = JsonArray(
        nodeIds.map { nodeId -> buildJsonObject { put("nodeId", nodeId) } },
    )

    private fun record(nodeId: String, status: NodeExecutionStatus): NodeExecutionRecord {
        val record = mockk<NodeExecutionRecord>()
        every { record.nodeId } returns nodeId
        every { record.status } returns status
        return record
    }
}
