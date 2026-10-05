package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.links.LinkCategory
import bosca.workops.model.links.TaskLink
import bosca.workops.model.links.TaskLinkType
import bosca.workops.model.task.Task
import bosca.workops.repository.TaskLinkRepository
import bosca.workops.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskDependencyServiceTest {

    private val tasks = mockk<TaskRepository>()
    private val links = mockk<TaskLinkRepository>()
    private val service = TaskDependencyServiceImpl(tasks, links)

    @Test
    fun `graph ignores missing incoming unknown and non dependency links while detecting a cycle`() = runTest {
        val principalId = UUID.random()
        val a = task("A")
        val b = task("B")
        val missingId = UUID.random()
        val blocks = linkType(LinkCategory.BLOCKS)
        val relates = linkType(LinkCategory.RELATES_TO)
        val missingTypeId = UUID.random()
        val incoming = TaskLink(
            linkTypeId = blocks.id,
            sourceTaskId = UUID.random(),
            targetTaskId = a.id,
            createdByPrincipalId = principalId,
        )
        val aToB = link(blocks.id, a.id, b.id, principalId)
        val aToMissing = link(blocks.id, a.id, missingId, principalId)
        val unsupported = link(relates.id, a.id, UUID.random(), principalId)
        val unknown = link(missingTypeId, a.id, UUID.random(), principalId)
        val bToA = link(blocks.id, b.id, a.id, principalId)
        coEvery { tasks.getActiveById(a.id) } returns a
        coEvery { tasks.getActiveById(b.id) } returns b
        coEvery { tasks.getActiveById(missingId) } returns null
        coEvery { links.listByTask(a.id) } returns listOf(incoming, aToB, aToMissing, unsupported, unknown)
        coEvery { links.listByTask(b.id) } returns listOf(bToA, aToB)
        coEvery { links.getLinkTypeById(blocks.id) } returns blocks
        coEvery { links.getLinkTypeById(relates.id) } returns relates
        coEvery { links.getLinkTypeById(missingTypeId) } returns null

        val graph = service.graph(a.id, 16)

        assertEquals(setOf(a.id, b.id), graph.nodes.map { it.taskId }.toSet())
        assertEquals(
            setOf(a.id to b.id, a.id to missingId, b.id to a.id),
            graph.edges.map { it.sourceTaskId to it.targetTaskId }.toSet(),
        )
        assertEquals(listOf(setOf(a.id, b.id)), graph.cycles.map { it.toSet() })
    }

    @Test
    fun `depth is clamped and missing roots produce an empty graph`() = runTest {
        val root = task("ROOT")
        val child = task("CHILD")
        val grandchild = task("GRANDCHILD")
        val type = linkType(LinkCategory.CAUSES)
        val principalId = UUID.random()
        coEvery { tasks.getActiveById(root.id) } returns root
        coEvery { tasks.getActiveById(child.id) } returns child
        coEvery { links.listByTask(root.id) } returns listOf(link(type.id, root.id, child.id, principalId))
        coEvery { links.listByTask(child.id) } returns listOf(link(type.id, child.id, grandchild.id, principalId))
        coEvery { links.getLinkTypeById(type.id) } returns type

        val oneLevel = service.graph(root.id, -5)
        assertEquals(setOf(root.id, child.id), oneLevel.nodes.map { it.taskId }.toSet())
        assertTrue(oneLevel.nodes.none { it.taskId == grandchild.id })

        val missing = UUID.random()
        coEvery { tasks.getActiveById(missing) } returns null
        val empty = service.graph(missing, 99)
        assertTrue(empty.nodes.isEmpty())
        assertTrue(empty.edges.isEmpty())
        assertTrue(empty.cycles.isEmpty())
    }

    @Test
    fun `graph stops queue growth at its documented node limit`() = runTest {
        val root = task("ROOT")
        val children = List(260) { task("CHILD-$it") }
        val type = linkType(LinkCategory.DUPLICATES)
        val principalId = UUID.random()
        coEvery { tasks.getActiveById(root.id) } returns root
        children.forEach { child ->
            coEvery { tasks.getActiveById(child.id) } returns child
            coEvery { links.listByTask(child.id) } returns emptyList()
        }
        coEvery { links.listByTask(root.id) } returns children.map { child ->
            link(type.id, root.id, child.id, principalId)
        }
        coEvery { links.getLinkTypeById(type.id) } returns type

        val graph = service.graph(root.id, 16)

        assertEquals(256, graph.nodes.size)
        assertEquals(260, graph.edges.size)
    }

    private fun task(key: String) = Task(
        id = UUID.random(),
        key = key,
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = key,
        reporterProfileId = UUID.random(),
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )

    private fun linkType(category: LinkCategory) = TaskLinkType(
        id = UUID.random(),
        name = category.name,
        inwardLabel = "inward",
        outwardLabel = "outward",
        category = category,
    )

    private fun link(typeId: UUID, source: UUID, target: UUID, principalId: UUID) = TaskLink(
        linkTypeId = typeId,
        sourceTaskId = source,
        targetTaskId = target,
        createdByPrincipalId = principalId,
    )
}
