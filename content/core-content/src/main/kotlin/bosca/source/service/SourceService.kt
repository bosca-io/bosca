package bosca.source.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.source.model.Source
import bosca.source.model.SourceInput

/**
 * Service for managing content sources. Sources represent external systems or origins
 * from which content is imported or synchronized (e.g., a CMS, an API, a file system).
 */
interface SourceService : Service {

    /**
     * Retrieves all sources defined in the system.
     *
     * @return the complete list of sources
     */
    suspend fun getAll(): List<Source>

    /**
     * Looks up a source by its identifier.
     *
     * @param id the source identifier
     * @return the source
     */
    suspend fun getById(id: UUID): Source

    /**
     * Resolves or creates a source for the given URL. Well-known providers
     * (Google Drive, Dropbox, etc.) are mapped to friendly names; unrecognized
     * hosts use the raw domain as the source name.
     *
     * @param url the external URL to resolve a source for
     * @return the existing or newly created source
     */
    suspend fun getOrCreateForUrl(url: String): Source

    /**
     * Creates a new source from the given input.
     *
     * @param input the source definition to create
     * @return the newly created source
     */
    suspend fun add(input: SourceInput): Source

    /**
     * Updates an existing source with new values.
     *
     * @param id the source identifier to update
     * @param input the updated source definition
     * @return the modified source
     */
    suspend fun edit(id: UUID, input: SourceInput): Source

    /**
     * Deletes a source by its identifier.
     *
     * @param id the source identifier to delete
     */
    suspend fun delete(id: UUID)
}
