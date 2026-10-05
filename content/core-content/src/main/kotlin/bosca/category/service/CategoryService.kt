package bosca.category.service

import bosca.category.model.Category
import bosca.category.model.CategoryInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing content categories. Categories are used to classify and organize
 * content items (such as metadata and collections) within the system.
 */
interface CategoryService : Service {

    /**
     * Retrieves all categories in the system.
     *
     * @return the complete list of categories
     */
    suspend fun getAll(): List<Category>

    /**
     * Retrieves categories matching the specified identifiers.
     *
     * @param ids the list of category identifiers to look up
     * @return the list of categories matching the given identifiers
     */
    suspend fun getAll(ids: List<UUID>): List<Category>

    /**
     * Creates a new category from the given input.
     *
     * @param input the category definition to create
     * @return the newly created category
     */
    suspend fun add(input: CategoryInput): Category

    /**
     * Updates an existing category with new values.
     *
     * @param id the identifier of the category to update
     * @param input the updated category definition
     * @return the modified category
     */
    suspend fun edit(id: UUID, input: CategoryInput): Category

    /**
     * Deletes a category by its identifier.
     *
     * @param id the identifier of the category to delete
     */
    suspend fun delete(id: UUID)
}
