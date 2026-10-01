package bosca.content.metadata.service

import bosca.content.metadata.model.*
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing structured data content associated with metadata entries. Data
 * objects represent typed, template-driven content that can be collaboratively edited
 * and versioned alongside their parent metadata.
 */
interface DataService : Service {

    /**
     * Retrieves a data object by its parent metadata identifier and version.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @return the data object, or null if not found
     */
    suspend fun getData(id: UUID, version: Int): Data?

    /**
     * Creates a new data object for the specified metadata version.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param input the data definition to persist
     */
    suspend fun addData(id: UUID, version: Int, input: DataInput)

    /**
     * Creates or replaces the data object associated with a metadata entry, using
     * the metadata's current version.
     *
     * @param metadata the parent metadata entry
     * @param input the data definition to persist
     */
    suspend fun setData(metadata: Metadata, input: DataInput)

    /**
     * Registers a batch loader for efficiently fetching data objects by metadata cache key.
     *
     * @param batch the batch accumulator to populate with data objects
     */
    suspend fun addToBatch(batch: Batch<MetadataCacheKeyId, Data>)

    /**
     * Assigns a data template to a data object, defining its schema and structure.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param templateMetadataId the template's metadata identifier
     * @param templateMetadataVersion the template's metadata version
     */
    suspend fun setTemplate(id: UUID, version: Int, templateMetadataId: UUID, templateMetadataVersion: Int)

    /**
     * Sets the data type classification for a data object.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @param type the data type to assign
     */
    suspend fun setType(id: UUID, version: Int, type: DataType)

    /**
     * Retrieves the collaboration configuration for a data object.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     * @return the collaboration configuration, or null if none exists
     */
    suspend fun getCollaboration(id: UUID, version: Int): DataCollaboration?

    /**
     * Creates or updates the collaboration configuration for a data object.
     *
     * @param input the collaboration configuration to persist
     */
    suspend fun setCollaboration(input: DataCollaborationInput)

    /**
     * Removes the collaboration configuration from a data object.
     *
     * @param id the parent metadata identifier
     * @param version the metadata version number
     */
    suspend fun removeCollaboration(id: UUID, version: Int)

    /**
     * Marks the collaboration collections data as dirty for a metadata entry, triggering
     * re-synchronization of collaborative collection data.
     *
     * @param metadataId the metadata identifier
     */
    suspend fun markCollaborationCollectionsDirty(metadataId: UUID)

    /**
     * Marks the collaboration relationships data as dirty for a metadata entry, triggering
     * re-synchronization of collaborative relationship data.
     *
     * @param metadataId the metadata identifier
     */
    suspend fun markCollaborationRelationshipsDirty(metadataId: UUID)

    /**
     * Marks the collaboration attributes data as dirty for a metadata entry, triggering
     * re-synchronization of collaborative attribute data.
     *
     * @param metadataId the metadata identifier
     */
    suspend fun markCollaborationAttributesDirty(metadataId: UUID)
}
