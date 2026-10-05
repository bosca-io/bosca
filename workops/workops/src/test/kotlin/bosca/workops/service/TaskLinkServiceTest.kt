@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.model.LinkCycleException
import bosca.workops.model.LinkGraphTooDeepException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.links.CreateTaskLinkTypeInput
import bosca.workops.model.links.LinkCategory
import bosca.workops.model.links.TaskLink
import bosca.workops.model.links.TaskLinkInput
import bosca.workops.model.links.TaskLinkType
import bosca.workops.model.links.UpdateTaskLinkTypeInput
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.dispatch
import bosca.workops.model.task.Task
import bosca.workops.repository.TaskLinkRepository
import bosca.profile.profile.service.ProfileService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TaskLinkServiceTest {

    private val repository = mockk<TaskLinkRepository>()
    private val tasks = mockk<TaskService>()
    private val security = mockk<SecurityService>()
    private val profiles = mockk<ProfileService>()
    private val service = TaskLinkServiceImpl(repository, tasks, security, profiles)
    private val principalId = UUID.random()
    private val source = task("SRC-1")
    private val target = task("DST-1")

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        mockkStatic("bosca.workops.model.notification.NotificationDeliveryRequestedExtKt")
        coEvery { any<NotificationDeliveryRequested>().dispatch() } just Runs
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.workops.model.notification.NotificationDeliveryRequestedExtKt")
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `reads and link type lifecycle delegate exact values`() = runTest {
        val type = linkType(LinkCategory.RELATES_TO)
        val link = link(type.id)
        val create = CreateTaskLinkTypeInput("Relates", "is related by", "relates", LinkCategory.RELATES_TO)
        val update = UpdateTaskLinkTypeInput("Related", "is related", "relates", LinkCategory.CUSTOM, 3)
        coEvery { repository.listLinkTypes() } returns listOf(type)
        coEvery { repository.getLinkTypeById(type.id) } returns type
        coEvery { repository.getById(link.id) } returns link
        coEvery { repository.listByTask(source.id) } returns listOf(link)
        coEvery { repository.addLinkType(any(), any(), any(), any()) } returns type
        coEvery { repository.updateLinkType(type.id, "Related", "is related", "relates", LinkCategory.CUSTOM, 3) } returns type
        coEvery { repository.deleteById(link.id) } just Runs
        coEvery { repository.deleteLinkTypeById(type.id) } just Runs

        assertEquals(listOf(type), service.listLinkTypes())
        assertSame(type, service.getLinkType(type.id))
        assertSame(link, service.getById(link.id))
        assertEquals(listOf(link), service.listForTask(source.id))
        assertSame(type, service.createLinkType(create))
        assertSame(type, service.updateLinkType(type.id, update))
        service.unlink(link.id)
        service.deleteLinkType(type.id)
        coVerify(exactly = 1) { repository.deleteLinkTypeById(type.id) }

        coEvery { repository.updateLinkType(any(), any(), any(), any(), any(), any()) } returns null
        assertFailsWith<IllegalStateException> { service.updateLinkType(type.id, update) }
    }

    @Test
    fun `link validates endpoints type self links cycles and graph bound`() = runTest {
        val type = linkType(LinkCategory.BLOCKS)
        val input = TaskLinkInput(type.id, source.id, target.id)
        assertFailsWith<WorkOpsValidationException> {
            service.link(input.copy(targetTaskId = source.id), principalId)
        }

        coEvery { repository.getLinkTypeById(type.id) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.link(input, principalId) }

        coEvery { repository.getLinkTypeById(type.id) } returns type
        coEvery { tasks.getById(source.id) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.link(input, principalId) }

        coEvery { tasks.getById(source.id) } returns source
        coEvery { tasks.getById(target.id) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.link(input, principalId) }

        coEvery { tasks.getById(target.id) } returns target
        coEvery { repository.blockedTargets(target.id, LinkCategory.BLOCKS) } returns listOf(source.id)
        coEvery { repository.blockedTargets(source.id, LinkCategory.BLOCKS) } returns emptyList()
        assertFailsWith<LinkCycleException> { service.link(input, principalId) }

        val graph = List(258) { UUID.random() }
        coEvery { tasks.getById(graph.first()) } returns target.copy(id = graph.first())
        coEvery { repository.blockedTargets(any(), LinkCategory.BLOCKS) } answers {
            val index = graph.indexOf(firstArg())
            if (index in 0 until graph.lastIndex) listOf(graph[index + 1]) else emptyList()
        }
        assertFailsWith<LinkGraphTooDeepException> {
            service.link(input.copy(targetTaskId = graph.first()), principalId)
        }
    }

    @Test
    fun `successful and idempotent links resolve actor profiles and dispatch both task notifications`() = runTest {
        val type = linkType(LinkCategory.RELATES_TO)
        val input = TaskLinkInput(type.id, source.id, target.id)
        val inserted = link(type.id)
        val principalProfileId = UUID.random()
        val fallbackProfileId = UUID.random()
        val principalWithProfile = Principal(id = principalId, primaryProfileId = principalProfileId)
        val principalWithoutProfile = Principal(id = principalId)
        val fallbackProfile = mockk<bosca.profile.model.Profile> {
            every { id } returns fallbackProfileId
        }
        coEvery { repository.getLinkTypeById(type.id) } returns type
        coEvery { tasks.getById(source.id) } returns source
        coEvery { tasks.getById(target.id) } returns target
        coEvery { repository.add(any(), any(), any(), any()) } returns inserted

        coEvery { security.getPrincipalById(principalId) } returns principalWithProfile
        assertSame(inserted, service.link(input, principalId))

        coEvery { security.getPrincipalById(principalId) } returns principalWithoutProfile
        coEvery { profiles.getPrimaryProfile(principalWithoutProfile) } returns fallbackProfile
        assertSame(inserted, service.link(input, principalId))

        coEvery { security.getPrincipalById(principalId) } returns null
        assertSame(inserted, service.link(input, principalId))
        coVerify(exactly = 6) { any<NotificationDeliveryRequested>().dispatch() }

        coEvery { repository.add(any(), any(), any(), any()) } returns null
        coEvery { repository.getByTriple(source.id, target.id, type.id) } returns inserted
        assertSame(inserted, service.link(input, principalId))

        coEvery { repository.getByTriple(source.id, target.id, type.id) } returns null
        assertFailsWith<IllegalStateException> { service.link(input, principalId) }
    }

    private fun linkType(category: LinkCategory) = TaskLinkType(
        id = UUID.random(),
        name = category.name,
        inwardLabel = "is linked by",
        outwardLabel = "links",
        category = category,
    )

    private fun link(typeId: UUID) = TaskLink(
        id = UUID.random(),
        linkTypeId = typeId,
        sourceTaskId = source.id,
        targetTaskId = target.id,
        createdByPrincipalId = principalId,
    )

    private fun task(key: String) = Task(
        id = UUID.random(),
        key = key,
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = key,
        reporterProfileId = UUID.random(),
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )
}
