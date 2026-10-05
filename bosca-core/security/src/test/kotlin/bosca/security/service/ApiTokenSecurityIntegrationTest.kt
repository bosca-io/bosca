@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.security.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.redis.RedisCacheManager
import bosca.cache.redis.RedisCacheScripts
import bosca.cache.withRequestCache
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pubsub.PubSubService
import bosca.security.events.PrincipalMarkedDeleted
import bosca.security.events.dispatch
import bosca.security.graphql.ApiTokensMutationController
import bosca.security.model.Group
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.model.PrincipalGroup
import bosca.security.repository.*
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import bosca.test.resources.SharedPostgreSQLContainer
import bosca.test.resources.SharedValkeyContainer
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.every
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Real PostgreSQL repositories and two Redis-backed request-cache clients exercise account invalidation. */
class ApiTokenSecurityIntegrationTest {
    @Test
    fun `restricted tokens cannot widen persisted credentials and deleted accounts fail across warm and cold nodes`(): Unit = runBlocking {
        val postgres = SharedPostgreSQLContainer().withReuse(true)
        val valkey = SharedValkeyContainer().withExposedPorts(6379).withReuse(true)
        postgres.start()
        valkey.start()
        val pool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 4),
            key = "api-token-security-integration",
        ))
        val connections = valkey.newConnectionPool(4)
        val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val managers = List(2) { RedisCacheManager(connections, RedisCacheScripts(connections), cleanupScope) }
        val json = Json { serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        } }
        val credentials = PrincipalCredentialsRepositoryImpl()
        val principals = PrincipalRepositoryImpl()
        val groups = GroupRepositoryImpl()
        val memberships = PrincipalGroupRepositoryImpl()
        val pubsub = mockk<PubSubService>(relaxed = true)
        every { pubsub.subscribe(any(), any<kotlinx.serialization.DeserializationStrategy<String>>()) } returns emptyFlow()

        suspend fun <T> request(node: Int, block: suspend () -> T): T {
            provides<CacheManager>(overrideExisting = true) { managers[node] }
            val manager = pool.connection()
            try {
                return withContext(manager.asCoroutineContext()) { withRequestCache { block() } }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
        fun security() = SecurityServiceImpl(
            groups, principals, memberships, PrincipalRefreshTokenRepositoryImpl(), mockk(), credentials, json,
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), pubsub,
        )
        try {
            ProviderRegistry.clear()
            provides<ConnectionPool> { pool }
            provides<Json> { json }
            provides<RequestCacheSerializer> { RequestCacheSerializerImpl(json) }
            FlywayMigration(pool).migrate(listOf(CoreMigration()))
            mockkStatic("bosca.security.events.PrincipalMarkedDeletedExtKt")
            coEvery { any<PrincipalMarkedDeleted>().dispatch() } returns Unit
            provides<CacheManager> { managers[0] }
            val first = security()
            provides<CacheManager>(overrideExisting = true) { managers[1] }
            val second = security()
            val evaluator = GroupEvaluator(first)
            val tokens = listOf(
                ApiTokenServiceImpl(credentials, first, evaluator, pubsub),
                ApiTokenServiceImpl(credentials, second, evaluator, pubsub),
            )
            val principal = request(0) { transaction {
                val principal = principals.add(Principal(verified = true, anonymous = false))
                val admin = assertNotNull(groups.getGroupByName("administrators", GroupType.SYSTEM))
                memberships.add(PrincipalGroup(principal.id, admin.id))
                principal
            } }
            val created = request(0) { tokens[0].createToken(principal.id, ApiTokenInput("read only", scopes = listOf("content:view")), principal.id) }
            for (node in 0..1) {
                val authenticated = request(node) { tokens[node].authenticate(created.rawToken, null) }
                assertEquals(principal.id, authenticated.id)
                val authentication = mockk<AuthenticationContext>()
                coEvery { authentication.principal() } returns authenticated
                assertFailsWith<SecurityException> {
                    request(node) { ApiTokensMutationController(tokens[node], evaluator).edit(
                        authentication, created.credential.id, "escalated", null, listOf("security:manage"),
                    ) }
                }
            }
            assertEquals(listOf("content:view"), request(0) {
                credentials.getById(created.credential.id)?.let {
                    (it.attributes as ApiTokenCredentialAttributes).scopes
                }
            })
            request(0) { first.markPrincipalDeleted(principal.id) }
            assertNotNull(request(1) { second.getPrincipalById(principal.id) }?.deletedAt)
            for (node in 0..1) assertFailsWith<SecurityException> {
                request(node) { tokens[node].authenticate(created.rawToken, null) }
            }
            val cold = ApiTokenServiceImpl(credentials, second, evaluator, pubsub)
            assertFailsWith<SecurityException> { request(1) { cold.authenticate(created.rawToken, null) } }
        } finally {
            unmockkStatic("bosca.security.events.PrincipalMarkedDeletedExtKt")
            ProviderRegistry.clear()
            cleanupScope.cancel()
            connections.close()
            pool.close()
            valkey.stop()
            postgres.stop()
        }
    }
}
