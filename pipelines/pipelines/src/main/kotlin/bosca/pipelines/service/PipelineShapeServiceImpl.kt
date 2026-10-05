package bosca.pipelines.service

import bosca.pipelines.model.PipelineNamedShape
import bosca.pipelines.node.ShapeField
import bosca.pipelines.repository.PipelineShapeRecord
import bosca.pipelines.repository.PipelineShapeRepository
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@ServiceImplementation
class PipelineShapeServiceImpl(
    private val repository: PipelineShapeRepository,
) : PipelineShapeService {

    // ShapeField is plain (String name/type), so a bare Json (no module) round-trips the jsonb `fields`.
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ShapeField.serializer())

    override suspend fun list(): List<PipelineNamedShape> = repository.findAll().map(::toModel)

    override suspend fun getByName(name: String): PipelineNamedShape? = repository.findByName(name)?.let(::toModel)

    override suspend fun save(name: String, fields: List<ShapeField>): PipelineNamedShape =
        toModel(repository.upsert(PipelineShapeRecord(name = name, fields = json.encodeToJsonElement(serializer, fields))))

    override suspend fun delete(name: String) = repository.delete(name)

    private fun toModel(record: PipelineShapeRecord): PipelineNamedShape =
        PipelineNamedShape(record.name, json.decodeFromJsonElement(serializer, record.fields))
}
