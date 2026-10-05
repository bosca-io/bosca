package bosca.source.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.cache.serializers.UnitKeySerializer
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.source.model.Source
import bosca.source.model.SourceInput
import bosca.source.repository.SourceRepository
import kotlinx.serialization.json.JsonObject
import java.net.URI

@ServiceImplementation
class SourceServiceImpl(private val repository: SourceRepository) : SourceService {

    private val sourcesAll = ServiceCache("sources:all", UnitKeySerializer) {
        repository.getAll()
    }

    private val sourcesById = ServiceCache("sources:id", UUIDKeySerializer) {
        repository.getById(it)
    }

    override suspend fun getAll(): List<Source> = sourcesAll.get(Unit) ?: emptyList()

    override suspend fun getById(id: UUID): Source = sourcesById.get(id) ?: throw NoSuchElementException("Source not found: $id")

    override suspend fun getOrCreateForUrl(url: String): Source {
        val host = try {
            URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
        val (name, description) = resolveProviderName(host)
        val existing = repository.getByName(name)
        if (existing != null) return existing
        val source = repository.add(Source(name = name, description = description, configuration = JsonObject(emptyMap())))
        sourcesAll.clear()
        return source
    }

    override suspend fun add(input: SourceInput): Source {
        val source = Source(
            name = input.name,
            description = input.description,
            configuration = input.configuration
        )
        val added = repository.add(source)
        sourcesAll.clear()
        return added
    }

    override suspend fun edit(id: UUID, input: SourceInput): Source {
        val existing = repository.getById(id) ?: throw NoSuchElementException("Source not found: $id")
        var updated = existing.copy(
            name = input.name,
            description = input.description,
            configuration = input.configuration
        )
        updated = repository.update(updated)
        sourcesAll.clear()
        sourcesById.remove(id)
        return updated
    }

    override suspend fun delete(id: UUID) {
        repository.deleteById(id)
        sourcesAll.clear()
        sourcesById.remove(id)
    }

    companion object {
        private val knownProviders = mapOf(
            "drive.google.com" to ("Google Drive" to "Google Drive file sharing"),
            "drive.usercontent.google.com" to ("Google Drive" to "Google Drive file sharing"),
            "docs.google.com" to ("Google Docs" to "Google Docs document sharing"),
            "dropbox.com" to ("Dropbox" to "Dropbox file sharing"),
            "www.dropbox.com" to ("Dropbox" to "Dropbox file sharing"),
            "dl.dropboxusercontent.com" to ("Dropbox" to "Dropbox file sharing"),
            "onedrive.live.com" to ("OneDrive" to "Microsoft OneDrive file sharing"),
            "1drv.ms" to ("OneDrive" to "Microsoft OneDrive file sharing"),
            "github.com" to ("GitHub" to "GitHub repository hosting"),
            "raw.githubusercontent.com" to ("GitHub" to "GitHub repository hosting"),
            "s3.amazonaws.com" to ("Amazon S3" to "Amazon S3 object storage"),
            "storage.googleapis.com" to ("Google Cloud Storage" to "Google Cloud Storage"),
        )

        private fun resolveProviderName(host: String): Pair<String, String> {
            knownProviders[host]?.let { return it }
            // Check if host is a subdomain of a known provider (e.g. my-bucket.s3.amazonaws.com)
            for ((domain, provider) in knownProviders) {
                if (host.endsWith(".$domain")) return provider
            }
            return host to "Imported from $host"
        }
    }
}
