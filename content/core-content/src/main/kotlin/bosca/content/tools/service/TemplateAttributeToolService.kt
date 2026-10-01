package bosca.content.tools.service

import bosca.content.tools.model.TemplateAttributeTool
import bosca.content.tools.model.TemplateAttributeToolInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing template attribute tools. These tools define external processing
 * capabilities (e.g., AI prompts, transformations) that can be associated with template
 * attributes to provide automated content enrichment or validation.
 */
interface TemplateAttributeToolService : Service {

    /**
     * Retrieves all template attribute tools defined in the system.
     *
     * @return the complete list of template attribute tools
     */
    suspend fun getAll(): List<TemplateAttributeTool>

    /**
     * Looks up a template attribute tool by its identifier.
     *
     * @param id the tool identifier
     * @return the template attribute tool, or null if not found
     */
    suspend fun get(id: UUID): TemplateAttributeTool?

    /**
     * Creates a new template attribute tool from the given input.
     *
     * @param tool the tool definition to create
     * @return the newly created template attribute tool
     */
    suspend fun add(tool: TemplateAttributeToolInput): TemplateAttributeTool

    /**
     * Updates an existing template attribute tool with new values.
     *
     * @param id the tool identifier to update
     * @param tool the updated tool definition
     * @return the modified template attribute tool
     */
    suspend fun edit(id: UUID, tool: TemplateAttributeToolInput): TemplateAttributeTool

    /**
     * Deletes a template attribute tool by its identifier.
     *
     * @param id the tool identifier to delete
     */
    suspend fun delete(id: UUID)
}
