@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.profile.guide.service

import bosca.analytics.model.Events
import bosca.analytics.server.InProcessServerAnalyticsClient
import bosca.analytics.server.withAnalyticsContext
import bosca.analytics.server.AnalyticsContext
import bosca.analytics.service.EventProcessingService
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.service.GuideService
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.profile.guide.events.ProfileGuideCompleted
import bosca.profile.guide.events.ProfileGuideProgressAdded
import bosca.profile.guide.events.dispatch
import bosca.profile.guide.repository.ProfileGuideHistoryRepositoryImpl
import bosca.profile.guide.repository.ProfileGuideProgressRepositoryImpl
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercise generated repositories, transaction callbacks, and the existing in-process analytics client. */
class ProfileGuideAnalyticsIntegrationTest {
    @Test
    fun `committed steps emit saved timestamps and rollback or duplicate writes emit nothing`() = runBlocking {
        PostgreSQLContainer("pgvector/pgvector:pg17").use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("""
                        create table profile_guide_progress (
                            profile_id uuid not null, metadata_id uuid not null, version int not null,
                            attributes jsonb, started timestamptz default now(), modified timestamptz default now(),
                            completed_step_ids bigint[] not null default '{}', primary key(profile_id, metadata_id, version))
                    """.trimIndent())
                    sql.execute("""
                        create table profile_guide_history (
                            id bigserial primary key, profile_id uuid not null, metadata_id uuid not null,
                            version int not null, attributes jsonb, completed timestamptz)
                    """.trimIndent())
                }
            }
            val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
                url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 2,
            ), key = "guide-analytics-test"))
            ProviderRegistry.clear()
            provides<Json> { Json }
            provides<ConnectionPool> { pool }
            val manager = pool.connection()
            mockkStatic("bosca.profile.guide.events.ProfileGuideProgressAddedExtKt")
            mockkStatic("bosca.profile.guide.events.ProfileGuideCompletedExtKt")
            coEvery { any<ProfileGuideProgressAdded>().dispatch() } returns Unit
            coEvery { any<ProfileGuideCompleted>().dispatch() } returns Unit
            try {
                withContext(manager.asCoroutineContext()) {
                    val batches = mutableListOf<Events>()
                    val processing = mockk<EventProcessingService>()
                    coEvery { processing.queue(any(), any()) } coAnswers { batches += secondArg<Events>() }
                    val profile = UUID.random()
                    val guide = UUID.random()
                    val child = UUID.random()
                    val guideService = mockk<GuideService>()
                    coEvery { guideService.getGuideSteps(guide, 1) } returns listOf(
                        GuideStep(11, guide, 1, child, 1, 0),
                        GuideStep(12, guide, 1, child, 1, 1),
                    )
                    val progressRepository = ProfileGuideProgressRepositoryImpl()
                    val historyRepository = ProfileGuideHistoryRepositoryImpl()
                    val service = ProfileGuideServiceImpl(progressRepository, historyRepository, guideService,
                        InProcessServerAnalyticsClient(processing, "test-server"))
                    service.addProgress(profile, guide, 1, 0, null)
                    assertTrue(batches.isEmpty())
                    val saved = transaction {
                        val result = withAnalyticsContext(AnalyticsContext(appId = "client-app",
                            installationId = "client-installation", appVersion = "42", sessionId = "client-session")) {
                            service.addProgress(profile, guide, 1, 11, null)
                        }
                        assertTrue(batches.isEmpty(), "analytics must wait for the outer transaction")
                        result
                    }
                    val step = batches.single().events.single()
                    assertEquals("guide_step", step.element?.type)
                    assertEquals(setOf(guide.toString(), child.toString()), step.element?.content?.map { it.id }?.toSet())
                    assertEquals(profile.toString(), batches.single().context?.userId)
                    assertEquals("client-app", batches.single().context?.appId)
                    assertEquals("42", batches.single().context?.appVersion)
                    assertEquals("client-installation", batches.single().context?.device?.installationId)
                    assertEquals("client-session", batches.single().context?.sessionId)
                    assertEquals(saved.modified.toInstant().toEpochMilli(), step.created)
                    assertEquals(((saved.modified.nano / 1000) % 1000).toLong(), step.createdMicros)
                    assertNotNull(step.clientId)
                    service.addProgress(profile, guide, 1, 11, null)
                    assertEquals(1, batches.size)
                    service.addProgress(profile, guide, 1, 12, null)
                    assertEquals(listOf("guide_step", "guide"), batches.last().events.map { it.element?.type })
                    assertEquals(1L, historyRepository.countByProfileId(profile))
                    assertNull(progressRepository.findByProfileAndMetadata(profile, guide, 1))
                    assertEquals(3, batches.flatMap { it.events }.map { it.clientId }.toSet().size)
                    assertFailsWith<IllegalStateException> {
                        transaction {
                            service.addProgress(profile, guide, 1, 0, null)
                            service.addProgress(profile, guide, 1, 11, null)
                            error("rollback")
                        }
                    }
                    assertEquals(2, batches.size)
                    assertNull(progressRepository.findByProfileAndMetadata(profile, guide, 1))
                }
            } finally {
                unmockkStatic("bosca.profile.guide.events.ProfileGuideProgressAddedExtKt")
                unmockkStatic("bosca.profile.guide.events.ProfileGuideCompletedExtKt")
                withContext(NonCancellable) { manager.release(); pool.close() }
                ProviderRegistry.clear()
            }
        }
    }
}
