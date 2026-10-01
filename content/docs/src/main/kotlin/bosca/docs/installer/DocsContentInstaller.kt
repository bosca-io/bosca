package bosca.docs.installer

import bosca.docs.index.DocumentationIndexer
import bosca.docs.index.SourceDocument
import bosca.docs.model.ApiDocumentation
import bosca.docs.service.DocumentationService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.search.IndexStorageSystem
import bosca.search.service.SearchService
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class DocsContentInstaller(
    private val searchService: SearchService,
    private val storageSystemService: StorageSystemService,
    private val documentationService: DocumentationService,
    private val json: Json
) : PackageInstaller {
    override val version: String = "1.2.1"

    private val log = LoggerFactory.getLogger(DocsContentInstaller::class.java)

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val system = storageSystemService.getAll()
            .find { it.configuration.jsonObject["indexName"]?.jsonPrimitive?.content == "api-documentation" }

        if (system == null) {
            log.warn("API Documentation Index storage system not found, skipping content indexing")
            return
        }

        val indexStorageSystem = IndexStorageSystem(id = system.id, name = system.name)
        val indexer = DocumentationIndexer()
        val result = indexer.parseSourceFromClasspath()

        if (result.sourceDocs.isEmpty()) {
            log.info("No documentation found to index")
            return
        }

        // Phase 1: Store full SourceDocument JSON in database
        documentationService.deleteAll()
        val docsForDb = result.sourceDocs.map { sourceDoc ->
            ApiDocumentation(
                qualifiedName = sourceDoc.qualifiedName,
                content = json.encodeToJsonElement(SourceDocument.serializer(), sourceDoc),
            )
        }
        documentationService.upsertAll(docsForDb)
        log.info("Stored {} full documentation entries in database", docsForDb.size)

        // Phase 2: Index compact search documents in Meilisearch
        try {
            searchService.deleteAll(indexStorageSystem)
        } catch (e: Exception) {
            log.debug("Could not clear existing docs index (may not exist yet): {}", e.message)
        }
        val jsonDocs = result.searchDocs.map { json.encodeToJsonElement(it) }
        for (batch in jsonDocs.chunked(100)) {
            searchService.index(indexStorageSystem, batch)
        }
        log.info("Indexed {} compact search documents in Meilisearch", result.searchDocs.size)
    }
}
