package bosca.content.metadata.service

import bosca.cache.ServiceCache
import bosca.content.collaboration.Updater
import bosca.content.metadata.model.*
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.repository.DataCollaborationRepository
import bosca.content.metadata.repository.DataRepository
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory

@ServiceImplementation
class DataServiceImpl(
    private val metadataService: ObjectProvider<MetadataService>,
    private val dataRepository: DataRepository,
    private val dataCollaborationRepository: DataCollaborationRepository,
) : DataService {

    private val dataCache = ServiceCache(
        cacheName = "data",
        serializer = MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val results = dataRepository.getByMetadataIds(keys.map { it.id }).associateBy { MetadataCacheKeyId(it.metadataId, it.version) }
            for (key in keys) {
                batch.setData(key, results[key] ?: continue)
            }
        }
    ) {
        dataRepository.getByMetadataIdAndVersion(it.id, it.version ?: 1)
    }

    override suspend fun getData(id: UUID, version: Int): Data? =
        dataCache.get(MetadataCacheKeyId(id, version))

    override suspend fun addToBatch(batch: Batch<MetadataCacheKeyId, Data>) {
        dataCache.addToBatch(batch)
    }

    override suspend fun setTemplate(id: UUID, version: Int, templateMetadataId: UUID, templateMetadataVersion: Int) = transaction {
        dataRepository.setTemplate(id, version, templateMetadataId, templateMetadataVersion)
        dataCache.remove(MetadataCacheKeyId(id, version))
    }

    override suspend fun setType(id: UUID, version: Int, type: DataType) = transaction {
        dataRepository.setType(id, version, type)
        dataCache.remove(MetadataCacheKeyId(id, version))
    }

    override suspend fun addData(id: UUID, version: Int, input: DataInput) = transaction {
        dataRepository.add(Data(
            metadataId = id,
            version = version,
            type = input.type ?: DataType.ATTRIBUTES,
            templateMetadataId = input.templateMetadataId,
            templateMetadataVersion = input.templateMetadataVersion
        ))
        dataCache.remove(MetadataCacheKeyId(id, version))
    }

    override suspend fun setData(metadata: Metadata, input: DataInput) {
        dataRepository.edit(Data(
            metadataId = metadata.id,
            version = metadata.version,
            type = input.type ?: DataType.ATTRIBUTES,
            templateMetadataId = input.templateMetadataId,
            templateMetadataVersion = input.templateMetadataVersion
        ))
        dataCache.remove(MetadataCacheKeyId(metadata.id, metadata.version))
    }

    override suspend fun getCollaboration(id: UUID, version: Int): DataCollaboration? {
        return dataCollaborationRepository.getByMetadataIdAndVersion(id, version)
    }

    override suspend fun setCollaboration(input: DataCollaborationInput) = transaction {
        dataCollaborationRepository.setCollaboration(DataCollaboration(
            metadataId = input.metadataId,
            version = input.version,
            content = input.content ?: ByteArray(0)
        ))
    }

    override suspend fun removeCollaboration(id: UUID, version: Int) = transaction {
        dataCollaborationRepository.removeCollaboration(id, version)
    }

    override suspend fun markCollaborationCollectionsDirty(metadataId: UUID): Unit = transaction {
        log.warn("marking collaboration collections dirty for metadata: $metadataId")
        val metadata = metadataService.get().getById(metadataId) ?: return@transaction
        val collaboration = dataCollaborationRepository.getByMetadataIdAndVersionForUpdate(metadata.id, metadata.version) ?: return@transaction
        val content = Updater().use {
            val content = it.setCollectionsDirty(collaboration.content)
            if (!it.areCollectionsDirty(content)) error("collections are not dirty")
            content
        }
        setCollaboration(DataCollaborationInput(metadata.id, metadata.version, content))
    }

    override suspend fun markCollaborationRelationshipsDirty(metadataId: UUID): Unit = transaction {
        log.warn("marking collaboration relationships dirty for metadata: $metadataId")
        val metadata = metadataService.get().getById(metadataId) ?: return@transaction
        val collaboration = dataCollaborationRepository.getByMetadataIdAndVersionForUpdate(metadata.id, metadata.version) ?: return@transaction
        val content = Updater().use {
            val content = it.setRelationshipsDirty(collaboration.content)
            if (!it.areRelationshipsDirty(content)) error("relationships are not dirty")
            content
        }
        setCollaboration(DataCollaborationInput(metadata.id, metadata.version, content))
    }

    override suspend fun markCollaborationAttributesDirty(metadataId: UUID): Unit = transaction {
        log.warn("marking collaboration attributes dirty for metadata: $metadataId")
        val metadata = metadataService.get().getById(metadataId) ?: return@transaction
        val collaboration = dataCollaborationRepository.getByMetadataIdAndVersionForUpdate(metadata.id, metadata.version) ?: return@transaction
        val content = Updater().use {
            val content = it.setAttributesDirty(collaboration.content)
            if (!it.areAttributesDirty(content)) error("attributes are not dirty")
            content
        }
        setCollaboration(DataCollaborationInput(metadata.id, metadata.version, content))
    }

    companion object {
        private val log = LoggerFactory.getLogger(this::class.java)
    }
}
