package bosca.trait.service

import bosca.service.Service
import bosca.trait.model.Trait
import bosca.trait.model.TraitInput

/**
 * Service for managing traits. Traits are reusable behavioral tags that can be assigned to
 * content items to associate them with specific workflows and content type classifications.
 * Unlike categories (which are hierarchical), traits are flat labels that trigger processing
 * behaviors.
 */
interface TraitService : Service {

    /**
     * Retrieves all traits defined in the system.
     *
     * @return the complete list of traits
     */
    suspend fun getAll(): List<Trait>

    /**
     * Retrieves traits matching the specified identifiers.
     *
     * @param ids the list of trait identifiers to look up
     * @return the list of traits matching the given identifiers
     */
    suspend fun getAll(ids: List<String>): List<Trait>

    /**
     * Looks up a trait by its string identifier.
     *
     * @param id the trait identifier
     * @return the trait, or null if not found
     */
    suspend fun get(id: String): Trait?

    /**
     * Creates a new trait from the given input.
     *
     * @param input the trait definition to create
     * @return the newly created trait
     */
    suspend fun add(input: TraitInput): Trait

    /**
     * Updates an existing trait with new values.
     *
     * @param input the updated trait definition (the trait identifier is within the input)
     * @return the modified trait
     */
    suspend fun edit(input: TraitInput): Trait

    /**
     * Deletes a trait by its identifier.
     *
     * @param id the trait identifier to delete
     */
    suspend fun delete(id: String)

    /**
     * Retrieves the identifiers of all workflows associated with a trait. When content
     * items with this trait undergo state transitions, these workflows may be triggered.
     *
     * @param traitId the trait identifier
     * @return the list of associated workflow identifiers
     */
    suspend fun getWorkflowIds(traitId: String): List<String>

    /**
     * Retrieves the content types associated with a trait. This defines which content
     * types this trait is applicable to.
     *
     * @param traitId the trait identifier
     * @return the list of content type identifiers associated with this trait
     */
    suspend fun getContentTypes(traitId: String): List<String>

    /**
     * Retrieves all traits applicable to a specific content type.
     *
     * @param contentType the content type identifier to look up
     * @return the list of traits associated with the given content type
     */
    suspend fun getTraitsByContentType(contentType: String): List<Trait>
}
