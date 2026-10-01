package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.component.Component
import bosca.workops.model.milestone.Milestone
import bosca.workops.repository.ComponentRepository
import bosca.workops.repository.MilestoneRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgileLookupServiceTest {

    @Test
    fun `component lookups delegate while an empty batch avoids the repository`() = runTest {
        val repository = mockk<ComponentRepository>()
        val component = Component(
            id = UUID.random(),
            projectId = UUID.random(),
            name = "Platform",
        )
        coEvery { repository.getById(component.id) } returns component
        coEvery { repository.getByIds(listOf(component.id)) } returns listOf(component)
        coEvery { repository.listByProject(component.projectId) } returns listOf(component)
        val service = ComponentServiceImpl(repository)

        assertEquals(component, service.getById(component.id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(component), service.getByIds(listOf(component.id)))
        assertEquals(listOf(component), service.listByProject(component.projectId))
        coVerify(exactly = 0) { repository.getByIds(emptyList()) }
    }

    @Test
    fun `milestone lookups delegate while an empty batch avoids the repository`() = runTest {
        val repository = mockk<MilestoneRepository>()
        val milestone = Milestone(
            id = UUID.random(),
            programId = UUID.random(),
            name = "Public launch",
        )
        coEvery { repository.getById(milestone.id) } returns milestone
        coEvery { repository.getByIds(listOf(milestone.id)) } returns listOf(milestone)
        coEvery { repository.listByProgram(milestone.programId) } returns listOf(milestone)
        val service = MilestoneServiceImpl(repository)

        assertEquals(milestone, service.getById(milestone.id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(milestone), service.getByIds(listOf(milestone.id)))
        assertEquals(listOf(milestone), service.listByProgram(milestone.programId))
        coVerify(exactly = 0) { repository.getByIds(emptyList()) }
    }
}
