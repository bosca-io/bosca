package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.label.CreateLabelInput
import bosca.workops.model.label.Label
import bosca.workops.model.label.LabelScope
import bosca.workops.repository.LabelRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LabelServiceTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `lookups and deletion delegate while an empty batch avoids the repository`() = runTest {
        val repository = mockk<LabelRepository>()
        val portfolioId = UUID.random()
        val programId = UUID.random()
        val projectId = UUID.random()
        val label = Label(id = UUID.random(), name = "ready")
        coEvery { repository.getById(label.id) } returns label
        coEvery { repository.getByIds(listOf(label.id)) } returns listOf(label)
        coEvery { repository.listGlobal() } returns listOf(label)
        coEvery { repository.listByPortfolio(portfolioId) } returns listOf(label)
        coEvery { repository.listByProgram(programId) } returns listOf(label)
        coEvery { repository.listByProject(projectId) } returns listOf(label)
        coEvery { repository.deleteById(label.id) } returns Unit
        val service = LabelServiceImpl(repository)

        assertEquals(label, service.getById(label.id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(label), service.getByIds(listOf(label.id)))
        assertEquals(listOf(label), service.listGlobal())
        assertEquals(listOf(label), service.listByPortfolio(portfolioId))
        assertEquals(listOf(label), service.listByProgram(programId))
        assertEquals(listOf(label), service.listByProject(projectId))
        service.delete(label.id)
        coVerify(exactly = 0) { repository.getByIds(emptyList()) }
        coVerify(exactly = 1) { repository.deleteById(label.id) }
    }

    @Test
    fun `create normalizes valid labels in every scope`() = runTest {
        val repository = mockk<LabelRepository>()
        val portfolioId = UUID.random()
        val programId = UUID.random()
        val projectId = UUID.random()
        val global = Label(id = UUID.random(), name = "global-label")
        val portfolio = Label(
            id = UUID.random(),
            name = "portfolio-label",
            scope = LabelScope.PORTFOLIO,
            portfolioId = portfolioId,
        )
        val program = Label(
            id = UUID.random(),
            name = "program-label",
            scope = LabelScope.PROGRAM,
            programId = programId,
        )
        val project = Label(
            id = UUID.random(),
            name = "project-label",
            scope = LabelScope.PROJECT,
            projectId = projectId,
        )
        coEvery {
            repository.add("global-label", null, LabelScope.GLOBAL, null, null, null)
        } returns global
        coEvery {
            repository.add("portfolio-label", null, LabelScope.PORTFOLIO, portfolioId, null, null)
        } returns portfolio
        coEvery {
            repository.add("program-label", null, LabelScope.PROGRAM, null, programId, null)
        } returns program
        coEvery {
            repository.add("project-label", null, LabelScope.PROJECT, null, null, projectId)
        } returns project
        val service = LabelServiceImpl(repository)

        assertEquals(global, service.create(CreateLabelInput("GLOBAL-LABEL")))
        assertEquals(
            portfolio,
            service.create(CreateLabelInput("portfolio-label", scope = LabelScope.PORTFOLIO, portfolioId = portfolioId)),
        )
        assertEquals(
            program,
            service.create(CreateLabelInput("program-label", scope = LabelScope.PROGRAM, programId = programId)),
        )
        assertEquals(
            project,
            service.create(CreateLabelInput("project-label", scope = LabelScope.PROJECT, projectId = projectId)),
        )
    }

    @Test
    fun `create rejects invalid names and every mismatched scope parent`() = runTest {
        val repository = mockk<LabelRepository>()
        val service = LabelServiceImpl(repository)

        val invalidName = assertFailsWith<WorkOpsValidationException> {
            service.create(CreateLabelInput("not valid"))
        }
        assertEquals("name", invalidName.field)

        val mismatches = listOf(
            CreateLabelInput("global-portfolio", portfolioId = UUID.random()),
            CreateLabelInput("global-program", programId = UUID.random()),
            CreateLabelInput("global-project", projectId = UUID.random()),
            CreateLabelInput("portfolio", scope = LabelScope.PORTFOLIO),
            CreateLabelInput("program", scope = LabelScope.PROGRAM),
            CreateLabelInput("project", scope = LabelScope.PROJECT),
        )
        for (input in mismatches) {
            val failure = assertFailsWith<WorkOpsValidationException> {
                service.create(input)
            }
            assertEquals("scope", failure.field)
        }
        coVerify(exactly = 0) { repository.add(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `update normalizes valid names and reports invalid or stale updates`() = runTest {
        val repository = mockk<LabelRepository>()
        val id = UUID.random()
        val staleId = UUID.random()
        val updated = Label(id = id, name = "ready-now", colorHex = "#123456", version = 2)
        coEvery { repository.update(id, "ready-now", "#123456", 1) } returns updated
        coEvery { repository.update(staleId, "stale", null, 8) } returns null
        val service = LabelServiceImpl(repository)

        assertEquals(updated, service.update(id, "READY-NOW", "#123456", 1))
        val invalid = assertFailsWith<WorkOpsValidationException> {
            service.update(id, "not valid", null, 2)
        }
        val stale = assertFailsWith<OptimisticLockFailedException> {
            service.update(staleId, "stale", null, 8)
        }

        assertEquals("name", invalid.field)
        assertEquals("Label", stale.type)
        assertEquals(staleId, stale.id)
    }
}
