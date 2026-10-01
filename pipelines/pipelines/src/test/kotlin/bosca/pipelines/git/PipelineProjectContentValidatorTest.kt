package bosca.pipelines.git

import bosca.pipelines.service.PipelineService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PipelineProjectContentValidatorTest {

    private val pipelineService = mockk<PipelineService>(relaxed = true)
    private val validator = PipelineProjectContentValidator(pipelineService)

    private val repoId = Uuid.random()

    @Test
    fun `valid pipeline files pass validation`() = runTest {
        coEvery { pipelineService.validateGraph(any()) } returns null

        val errors = validator.validate(
            repoId,
            mapOf("pipelines/p.yaml" to "name: P\naccepted_input_type: Event\n"),
        )

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `parse failures are reported per file`() = runTest {
        val errors = validator.validate(
            repoId,
            mapOf("pipelines/broken.yaml" to "- not\n- a\n- mapping\n"),
        )

        assertEquals("pipelines/broken.yaml", errors.single().path)
    }

    @Test
    fun `graph validation failures are reported per file`() = runTest {
        coEvery { pipelineService.validateGraph(any()) } returns "unknown node type 'bogus'"

        val errors = validator.validate(
            repoId,
            mapOf("pipelines/p.yaml" to "name: P\naccepted_input_type: Event\n"),
        )

        assertEquals("pipelines/p.yaml", errors.single().path)
        assertTrue(errors.single().message.contains("bogus"))
    }

    @Test
    fun `non-pipeline files under the prefix are ignored`() = runTest {
        val errors = validator.validate(
            repoId,
            mapOf("pipelines/README.md" to "# docs, not a pipeline"),
        )

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `unknown-path files mixed with a valid pipeline produce no errors and never reach the validator`() = runTest {
        // The `UnknownPath -> Unit` arm: a README and a non-yaml file are skipped silently, while the
        // valid pipeline still passes. No UnknownPath file contributes an error.
        coEvery { pipelineService.validateGraph(any()) } returns null

        val errors = validator.validate(
            repoId,
            mapOf(
                "pipelines/README.md" to "# docs",
                "pipelines/notes.txt" to "just notes",
                "pipelines/p.yaml" to "name: P\naccepted_input_type: Event\n",
            ),
        )

        assertTrue(errors.isEmpty())
        // Only the one real pipeline file was graph-validated; the unknown-path files were ignored.
        coVerify(exactly = 1) { pipelineService.validateGraph(any()) }
    }
}
