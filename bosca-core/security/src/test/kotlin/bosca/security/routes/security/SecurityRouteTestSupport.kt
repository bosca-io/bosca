package bosca.security.routes.security

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.security.service.AuthenticationProviders
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.LanguageItem
import bosca.server.Parameters
import bosca.server.RequestOrigin
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.SessionManager
import bosca.server.auth.CallAuthenticationContext
import bosca.cache.serializers.StringKeySerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.EmptyCoroutineContext

internal class SecurityRouteTestCall(
    body: String = "",
    contentType: String? = "application/json",
    query: Map<String, String> = emptyMap(),
    form: Map<String, String> = emptyMap(),
    clientIp: String? = "192.0.2.1",
    appOrigin: String = "https://studio.example",
    languages: List<LanguageItem> = emptyList(),
) {
    val request = mockk<ServerRequest>()
    val response = mockk<ServerResponse>(relaxed = true)
    val sessions = mockk<SessionManager>(relaxed = true)
    val application = mockk<BoscaApplication> {
        every { json } returns Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        every { log } returns mockk(relaxed = true)
    }
    val authenticationContext = CallAuthenticationContext()
    val call = mockk<ServerCall>()

    init {
        every { request.contentType() } returns contentType?.let(ContentType::parse)
        coEvery { request.bodyText() } returns body
        every { request.queryParameters } returns Parameters.fromSingleValueMap(query)
        every { request.clientIp } returns clientIp
        every { request.appOrigin } returns appOrigin
        every { request.acceptLanguageItems() } returns languages
        every { request.origin } returns RequestOrigin("https", "studio.example", 443)
        every { call.request } returns request
        every { call.response } returns response
        every { call.sessions } returns sessions
        every { call.application } returns application
        every { call.authenticationContext } returns authenticationContext
        every { call.attributes } returns ConcurrentHashMap()
        every { call.respondRedirect(any(), any()) } answers {
            response.respondRedirect(firstArg(), secondArg())
        }
        coEvery {
            call.respond<Any>(
                message = any<Any>(),
                serializer = any<KSerializer<Any>>(),
            )
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val serializer = invocation.args[1] as KSerializer<Any>
            val text = application.json.encodeToString(serializer, invocation.args[0] as Any)
            response.respondText(text, ContentType.Application.Json)
        }
        coEvery { call.receiveParameters() } returns Parameters.fromSingleValueMap(form)
    }
}

@OptIn(InternalDI::class)
internal class SecurityRouteTestEnvironment(
    cacheManager: CacheManager,
) : AutoCloseable {
    private val connection = mockk<ConnectionManager>(relaxed = true)
    private val connectionPool = mockk<ConnectionPool> {
        every { connection() } returns connection
    }

    init {
        ProviderRegistry.clear()
        provides<ConnectionPool> { connectionPool }
        provides<CacheManager> { cacheManager }
        provides<RequestCacheSerializer> {
            RequestCacheSerializerImpl(Json { ignoreUnknownKeys = true })
        }
        provides<AuthenticationProviders> { AuthenticationProviders(arrayOf(null)) }
        mockkStatic("bosca.db.ConnectionManagerKt")
        every { connection.asCoroutineContext() } returns EmptyCoroutineContext
        coEvery { connection.release() } returns Unit
    }

    override fun close() {
        ProviderRegistry.clear()
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }
}

internal fun authRateLimitCacheManager(): CacheManager {
    val cache = InMemoryStringCache()
    return mockk {
        coEvery {
            maybeAddCache(any(), StringKeySerializer, any())
        } returns cache
    }
}

private class InMemoryStringCache : Cache<String> {
    private val values = mutableMapOf<String, String?>()
    override val keySerializer: CacheKeySerializer<String> = StringKeySerializer
    override val estimatedSize: Long
        get() = values.size.toLong()

    override suspend fun get(key: CacheKey<String>): CacheValue {
        val remoteKey = key.toRemoteKey()
        val exists = values.containsKey(remoteKey)
        return object : CacheValue {
            override val value: String? = values[remoteKey]
            override val exists: Boolean = exists
        }
    }

    override suspend fun getBatch(keys: List<CacheKey<String>>): List<CacheValue> =
        keys.map { get(it) }

    override suspend fun put(key: CacheKey<String>, value: String?) {
        values[key.toRemoteKey()] = value
    }

    override suspend fun putBatch(entries: List<Pair<CacheKey<String>, String?>>) {
        entries.forEach { (key, value) -> put(key, value) }
    }

    override suspend fun remove(key: CacheKey<String>, keyPrefix: Boolean): CacheValue? {
        val remoteKey = key.toRemoteKey()
        if (!values.containsKey(remoteKey)) return null
        val removed = values.remove(remoteKey)
        return object : CacheValue {
            override val value: String? = removed
            override val exists: Boolean = true
        }
    }

    override suspend fun clear() {
        values.clear()
    }

    override suspend fun evictExpiredItems() = Unit
}
