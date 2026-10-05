package bosca.content.tools.service

import bosca.content.tools.model.TemplateAttributeTool
import bosca.content.tools.model.TemplateAttributeToolInput
import bosca.content.tools.repository.TemplateAttributeToolRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class TemplateAttributeToolServiceImpl(
    private val toolRepository: TemplateAttributeToolRepository
) : TemplateAttributeToolService {

    override suspend fun getAll(): List<TemplateAttributeTool> {
        return toolRepository.getAll()
    }

    override suspend fun get(id: UUID): TemplateAttributeTool? {
        return toolRepository.get(id)
    }

    override suspend fun add(tool: TemplateAttributeToolInput): TemplateAttributeTool {
        return toolRepository.add(
            TemplateAttributeTool(
                id = UUID.random(),
                key = tool.key,
                name = tool.name,
                description = tool.description,
                query = tool.query,
                resultPath = tool.resultPath,
                configuration = tool.configuration
            )
        )
    }

    override suspend fun edit(id: UUID, tool: TemplateAttributeToolInput): TemplateAttributeTool {
        return toolRepository.edit(
            TemplateAttributeTool(
                id = id,
                key = tool.key,
                name = tool.name,
                description = tool.description,
                query = tool.query,
                resultPath = tool.resultPath,
                configuration = tool.configuration
            )
        )
    }

    override suspend fun delete(id: UUID) {
        toolRepository.delete(id)
    }
}
