package bosca.pipelines.service

import bosca.pipelines.node.ShapeField
import bosca.pipelines.repository.PipelineShapeRecord
import bosca.pipelines.repository.PipelineShapeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PipelineShapeServiceImplTest {

    private fun record(name: String, fieldName: String = "id") = PipelineShapeRecord(
        name = name,
        fields = JsonArray(
            listOf(
                buildJsonObject {
                    put("name", fieldName)
                    put("type", "String")
                    put("futureSetting", true)
                },
            ),
        ),
    )

    @Test
    fun `list and lookup map stored json while tolerating future fields`() = runTest {
        val repository = mockk<PipelineShapeRepository>()
        coEvery { repository.findAll() } returns listOf(record("request"), record("response", "result"))
        coEvery { repository.findByName("request") } returns record("request")
        coEvery { repository.findByName("missing") } returns null
        val service = PipelineShapeServiceImpl(repository)

        val shapes = service.list()
        assertEquals(listOf("request", "response"), shapes.map { it.name })
        assertEquals("result", shapes.last().fields.single().name)
        assertEquals("id", service.getByName("request")?.fields?.single()?.name)
        assertNull(service.getByName("missing"))
    }

    @Test
    fun `save serializes fields and delete delegates by name`() = runTest {
        val repository = mockk<PipelineShapeRepository>()
        val fields = listOf(ShapeField("email", "String"))
        coEvery { repository.upsert(any()) } answers { firstArg() }
        coEvery { repository.delete("contact") } returns Unit
        val service = PipelineShapeServiceImpl(repository)

        assertEquals(fields.map { it.name }, service.save("contact", fields).fields.map { it.name })
        service.delete("contact")

        coVerify(exactly = 1) {
            repository.upsert(match { it.name == "contact" })
            repository.delete("contact")
        }
    }
}
