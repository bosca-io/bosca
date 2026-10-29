package bosca.analytics.service

import bosca.analytics.configuration.AnalyticsQueryCacheConfiguration
import bosca.analytics.repository.QueryCacheEntryRepositoryImpl
import bosca.content.collection.model.Collection
import bosca.content.metadata.model.Metadata
import bosca.di.ObjectProvider
import bosca.git.service.SourceRefService
import bosca.security.model.AuthenticatedPrincipal
import bosca.serialization.UUID
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.SignedUrl
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * An [ObjectProvider] that reports nothing is available — used in tests that
 * instantiate analytics services without the git module on the classpath.
 */
internal object MissingSourceRefServiceProvider : ObjectProvider<SourceRefService> {
    override val type = SourceRefService::class
    override val exists: Boolean = false
    override suspend fun get(): SourceRefService = error("SourceRefService not available in tests")
}

/**
 * In-memory [ObjectStorageService] supporting just the string/stream operations
 * the query result cache uses; everything else fails loudly.
 */
internal class InMemoryObjectStorageService : ObjectStorageService {

    private val objects = ConcurrentHashMap<String, ByteArray>()

    override suspend fun getPath(metadata: Metadata, supplementaryId: UUID?): ObjectPath =
        error("not supported in tests")

    override suspend fun getPath(collection: Collection, supplementaryId: UUID?): ObjectPath =
        error("not supported in tests")

    override suspend fun getString(path: ObjectPath): String =
        objects[path.toString()]?.toString(Charsets.UTF_8) ?: error("no object at $path")

    override suspend fun getInputStream(path: ObjectPath): InputStream =
        objects[path.toString()]?.inputStream() ?: error("no object at $path")

    override suspend fun getInputStreamRange(path: ObjectPath, range: LongRange): InputStream =
        error("not supported in tests")

    override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
        val bytes = stream.readBytes()
        objects[path.toString()] = bytes
        return bytes.size.toLong()
    }

    override suspend fun delete(path: ObjectPath) {
        objects.remove(path.toString())
    }

    override suspend fun getSignedDownloadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal?,
        metadata: Metadata,
        supplementaryId: UUID?,
        filename: Boolean,
    ): SignedUrl = error("not supported in tests")

    override suspend fun getSignedUploadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal,
        metadata: Metadata,
        supplementaryId: UUID?,
    ): SignedUrl = error("not supported in tests")

    override suspend fun getSignedDownloadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal?,
        collection: Collection,
        supplementaryId: UUID?,
        filename: Boolean,
    ): SignedUrl = error("not supported in tests")

    override suspend fun getSignedUploadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal,
        collection: Collection,
        supplementaryId: UUID?,
    ): SignedUrl = error("not supported in tests")
}

/**
 * Provides a real [AnalyticsQueryResultCacheServiceImpl] backed by in-memory
 * object storage, constructed lazily so it only initializes after the test has
 * populated the provider registry.
 */
internal class TestResultCacheServiceProvider(
    private val json: Json,
    private val configuration: AnalyticsQueryCacheConfiguration = AnalyticsQueryCacheConfiguration(),
) : ObjectProvider<AnalyticsQueryResultCacheService> {
    override val type = AnalyticsQueryResultCacheService::class
    override val exists: Boolean = true
    val instance by lazy {
        AnalyticsQueryResultCacheServiceImpl(
            QueryCacheEntryRepositoryImpl(),
            InMemoryObjectStorageService(),
            json,
            configuration,
        )
    }
    override suspend fun get(): AnalyticsQueryResultCacheService = instance
}
