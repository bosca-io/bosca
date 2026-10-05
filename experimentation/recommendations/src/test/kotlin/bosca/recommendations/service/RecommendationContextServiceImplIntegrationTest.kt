@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.recommendations.service

import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.recommendations.graphql.RecommendationContextsControllerProvider
import bosca.recommendations.graphql.RecommendationContextsMutationControllerProvider
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.content.collection.service.CollectionService
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.recommendations.configuration.RecommendationsMigration
import bosca.recommendations.configuration.JobQueueNames
import bosca.recommendations.createProfileAttributeSignalsPrerequisites
import bosca.recommendations.model.RecommendationCollectionFilterInput
import bosca.recommendations.model.RecommendationContentFilterInput
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextInput
import bosca.recommendations.model.RecommendationMetadataFilterInput
import bosca.recommendations.model.RecommendationWeightsInput
import bosca.recommendations.model.RecommendationTypePreferenceInput
import bosca.recommendations.model.RecommendationTrainingStatus
import bosca.recommendations.repository.RecommendationContextRepositoryImpl
import bosca.recommendations.repository.RecommendationContextRepository
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.AfterClass

/** Real-Postgres coverage for saved recommendation contexts and their typed JSON filters. */
class RecommendationContextServiceImplIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_recommendation_context_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 5),
                key = "test",
            ),
        )
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    private lateinit var service: RecommendationContextServiceImpl
    private lateinit var classifier: RecommendationContextClassifierImpl
    private lateinit var jobQueue: JobQueue

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }
        jobQueue = mockk(relaxed = true)
        provides<JobQueue>(name = JobQueueNames.recommendationsJobQueue, singleton = true) { jobQueue }
        if (!schemaInitialized) {
            withDb {
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.profiles (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.analytics_queries (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE SCHEMA IF NOT EXISTS segmentation") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS segmentation.segments (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.scheduled_jobs (id uuid PRIMARY KEY)") { it.execute() }
                createProfileAttributeSignalsPrerequisites()
            }
            runBlocking { FlywayMigration(pool).migrate(listOf(RecommendationsMigration())) }
            schemaInitialized = true
        }
        withDb {
            connection().useStatement("DELETE FROM recommendations.contexts WHERE type <> 'default'") { it.execute() }
        }
        val repository = RecommendationContextRepositoryImpl()
        classifier = RecommendationContextClassifierImpl(
            repository,
            mockk<MetadataService>(relaxed = true),
            mockk<CollectionService>(relaxed = true),
        )
        service = RecommendationContextServiceImpl(repository, classifier)
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) { block() }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    @Test
    fun `saving contexts queues no jobs and explicit training captures the selected saved context`() {
        withDb {
            val context = service.add(RecommendationContextInput(type = "manual-training", name = "Manual training"))
            val other = service.add(RecommendationContextInput(type = "untrained", name = "Untrained"))
            assertTrue(service.getModels(context.id).isEmpty())
            val edited = service.edit(context.id, RecommendationContextInput(
                type = context.type, name = "Saved name", weights = RecommendationWeightsInput(defaultTypePreference = 0.9),
            ))
            assertEquals(context.revision + 1, edited.revision)
            assertEquals(context.selectionRevision, edited.selectionRevision)
            assertTrue(service.getModels(context.id).isEmpty())
            coVerify(exactly = 0) { jobQueue.enqueue(any()) }

            provides<RecommendationContextService> { service }
            provides<GroupEvaluator> { mockk(relaxed = true) }
            provides<ArtifactPermissionEvaluator> { mockk(relaxed = true) }
            val model = RecommendationContextsMutationControllerProvider().get().trainModel(mockk(relaxed = true), context.id)
            assertEquals(listOf(model), service.getModels(context.id))
            assertEquals(edited.revision, model.revision)
            assertEquals("Saved name", model.context.name)
            assertEquals(0.9, model.context.weights.defaultTypePreference)
            assertEquals(RecommendationTrainingStatus.QUEUED, model.status)
            assertEquals(edited.selectionRevision + 1, model.selectionRevision)
            assertTrue(service.getModels(other.id).isEmpty())
            coVerify(exactly = 1) { jobQueue.enqueue(match {
                it.getDefinition() == Json.encodeToJsonElement(
                    bosca.recommendations.jobs.TrainModelJob.serializer(),
                    bosca.recommendations.jobs.TrainModelJob(contextModelVersion = model.version),
                )
            }) }

            val saved = service.edit(context.id, RecommendationContextInput(type = context.type, name = "Later settings"))
            assertEquals(model.selectionRevision, saved.selectionRevision)
            assertEquals(listOf(model), service.getModels(context.id))
            assertFailsWith<NoSuchElementException> { service.trainModel(UUID.random()) }
        }
    }

    @Test
    fun `explicit recomputation queues only content reclassification without training`() {
        withDb {
            val context = service.add(RecommendationContextInput(type = "manual-recompute", name = "Manual recompute"))
            provides<RecommendationContextService> { service }
            provides<GroupEvaluator> { mockk(relaxed = true) }
            provides<ArtifactPermissionEvaluator> { mockk(relaxed = true) }
            coVerify(exactly = 0) { jobQueue.enqueue(any()) }
            assertTrue(RecommendationContextsMutationControllerProvider().get().recompute(mockk(relaxed = true)))
            coVerify(exactly = 1) { jobQueue.enqueue(match {
                it.getDefinition() == buildJsonObject { put("trainModels", false) }
            }) }
            coVerify(exactly = 1) { jobQueue.enqueue(any()) }
            assertTrue(service.getModels(context.id).isEmpty())
        }
    }

    @Test
    fun `deleting a context before its training callback locks it leaves no model to activate`() {
        withDb {
            for (callback in listOf("export", "complete", "activate")) {
                val context = service.add(RecommendationContextInput(type = "deleted-$callback", name = "Deleted $callback"))
                val model = service.trainModel(context.id)
                service.startModel(model.version)
                if (callback != "export") service.exportModel(model.version, false)
                if (callback == "activate") service.completeModel(model.version)

                val delegate = RecommendationContextRepositoryImpl()
                val interleaved = object : RecommendationContextRepository by delegate {
                    override suspend fun lock(id: UUID): RecommendationContext? {
                        val deletingManager = pool.connection()
                        try {
                            withContext(deletingManager.asCoroutineContext()) { service.delete(id) }
                        } finally {
                            withContext(NonCancellable) { deletingManager.release() }
                        }
                        return delegate.lock(id)
                    }
                }
                val completing = RecommendationContextServiceImpl(interleaved, classifier)
                when (callback) {
                    "export" -> assertFailsWith<NoSuchElementException> { completing.exportModel(model.version, false) }
                    "complete" -> completing.completeModel(model.version)
                    "activate" -> completing.activateLoadedModel(model.version, model.selectionRevision)
                }
                assertNull(service.getById(context.id))
                assertNull(service.getModel(model.version))
            }
        }
    }

    @Test
    fun `failure committed after completion reads running cannot replace the active model`() {
        withDb {
            val context = service.add(RecommendationContextInput(type = "completion-race", name = "Completion race"))
            val previous = service.trainModel(context.id)
            service.startModel(previous.version)
            service.exportModel(previous.version, false)
            service.completeModel(previous.version)
            service.edit(context.id, RecommendationContextInput(type = context.type, name = context.name))
            val pending = service.trainModel(context.id)
            service.startModel(pending.version)
            service.exportModel(pending.version, false)

            val delegate = RecommendationContextRepositoryImpl()
            val interleaved = object : RecommendationContextRepository by delegate {
                override suspend fun completeModel(version: Long): Int {
                    // A separate request commits failure after the completion request's snapshot read.
                    val failureManager = pool.connection()
                    try {
                        withContext(failureManager.asCoroutineContext()) {
                            delegate.failModel(version, "training failed during completion")
                        }
                    } finally {
                        withContext(NonCancellable) { failureManager.release() }
                    }
                    return delegate.completeModel(version)
                }
            }
            val completing = RecommendationContextServiceImpl(interleaved, classifier)
            assertFailsWith<IllegalStateException> { completing.completeModel(pending.version) }
            assertEquals(RecommendationTrainingStatus.FAILED, service.getModel(pending.version)?.status)
            assertEquals(previous.version, service.getById(context.id)?.activeModelVersion)
            assertEquals(RecommendationTrainingStatus.COMPLETED, service.getModel(previous.version)?.status)
        }
    }

    @Test
    fun `deleting a terminal model removes history and queues cleanup while protecting live selections`() {
        withDb {
            val context = service.add(RecommendationContextInput(type = "delete-model", name = "Delete model"))
            val first = service.trainModel(context.id)
            assertFailsWith<IllegalArgumentException> { service.deleteModel(context.id, first.version) }
            service.startModel(first.version)
            service.exportModel(first.version, true)
            assertFailsWith<IllegalArgumentException> { service.deleteModel(context.id, first.version) }
            service.completeModel(first.version)
            assertFailsWith<IllegalArgumentException> { service.deleteModel(context.id, first.version) }
            service.edit(context.id, RecommendationContextInput(type = context.type, name = context.name))
            val second = service.trainModel(context.id)
            service.startModel(second.version)
            service.exportModel(second.version, true)
            service.completeModel(second.version)
            service.activateModel(context.id, first.version)
            assertFailsWith<IllegalArgumentException> { service.deleteModel(context.id, first.version) }
            assertFailsWith<IllegalArgumentException> { service.deleteModel(context.id, second.version) }
            val other = service.add(RecommendationContextInput(type = "other-delete", name = "Other"))
            assertFailsWith<IllegalArgumentException> { service.deleteModel(other.id, first.version) }
            service.activateLoadedModel(first.version, checkNotNull(service.getById(context.id)).selectionRevision)
            service.pinModel(context.id, second.version, true)
            service.deleteModel(context.id, second.version)
            assertNull(service.getModel(second.version))
            assertEquals(listOf(first.version), service.getModels(context.id).map { it.version })
            coVerify { jobQueue.enqueue(match {
                it.getDefinition() == Json.encodeToJsonElement(
                    bosca.recommendations.jobs.DeleteContextModelArtifactsJob.serializer(),
                    bosca.recommendations.jobs.DeleteContextModelArtifactsJob(context.id, second.version),
                )
            }) }
            val failed = service.trainModel(other.id)
            service.failModel(failed.version, "Training failed")
            service.deleteModel(other.id, failed.version)
            assertNull(service.getModel(failed.version))
            assertFailsWith<NoSuchElementException> { service.deleteModel(other.id, failed.version) }
            assertFailsWith<NoSuchElementException> { service.deleteModel(UUID.random(), first.version) }
            service.edit(context.id, RecommendationContextInput(type = context.type, name = context.name))
            val trainingFromFirst = service.trainModel(context.id)
            service.edit(context.id, RecommendationContextInput(type = context.type, name = context.name))
            val newer = service.trainModel(context.id)
            service.startModel(newer.version)
            service.exportModel(newer.version, true)
            service.completeModel(newer.version)
            assertFailsWith<IllegalArgumentException> { service.deleteModel(context.id, first.version) }
            service.failModel(trainingFromFirst.version, "Cancelled")
            service.deleteModel(context.id, first.version)
            assertNull(service.getModel(first.version))
        }
    }

    @Test
    fun `completion that wins the context lock remains idempotent for a waiting callback`() {
        withDb {
            val context = service.add(RecommendationContextInput(type = "duplicate-completion", name = "Duplicate completion"))
            val model = service.trainModel(context.id)
            service.startModel(model.version)
            service.exportModel(model.version, false)
            val delegate = RecommendationContextRepositoryImpl()
            val interleaved = object : RecommendationContextRepository by delegate {
                override suspend fun lock(id: UUID): RecommendationContext? {
                    val completingManager = pool.connection()
                    try {
                        withContext(completingManager.asCoroutineContext()) {
                            service.completeModel(model.version)
                        }
                    } finally {
                        withContext(NonCancellable) { completingManager.release() }
                    }
                    return delegate.lock(id)
                }
            }
            RecommendationContextServiceImpl(interleaved, classifier).completeModel(model.version)
            assertEquals(model.version, service.getById(context.id)?.activeModelVersion)
            assertEquals(RecommendationTrainingStatus.COMPLETED, service.getModel(model.version)?.status)
            assertNull(service.getModel(model.version)?.failure)
        }
    }

    @Test
    fun `training status uses lowercase PostgreSQL labels and maps to Kotlin enums`() {
        withDb {
            connection().useStatement("SELECT unnest(enum_range(NULL::recommendations.training_status))::text") { statement ->
                statement.executeQuery().use { rows ->
                    val labels = mutableListOf<String>()
                    while (rows.next()) labels.add(rows.getString(1))
                    assertEquals(listOf("queued", "running", "completed", "failed"), labels)
                }
            }
            val context = service.add(RecommendationContextInput(type = "enum-labels", name = "Enum labels"))
            val model = service.trainModel(context.id)
            assertEquals(RecommendationTrainingStatus.QUEUED, model.status)
            service.startModel(model.version)
            assertEquals(RecommendationTrainingStatus.RUNNING, service.getModel(model.version)?.status)
            service.failModel(model.version, "test failure")
            assertEquals(RecommendationTrainingStatus.FAILED, service.getModel(model.version)?.status)
        }
    }

    @Test
    fun `export callbacks are idempotent and cannot change artifact selection`() {
        withDb {
            val context = service.add(RecommendationContextInput(type = "immutable", name = "Immutable"))
            val model = service.trainModel(context.id)
            service.startModel(model.version)
            service.exportModel(model.version, false)
            service.exportModel(model.version, false)
            assertFailsWith<IllegalArgumentException> { service.exportModel(model.version, true) }
            service.completeModel(model.version)
            service.exportModel(model.version, false)
            assertEquals(model.version, service.getById(context.id)?.activeModelVersion)
            assertNull(service.getById(context.id)?.requestedModelVersion)
        }
    }

    @Test
    fun `artifact authorized callback persists exports while rejected callbacks leave the model unchanged`() {
        withDb {
            val context = service.add(RecommendationContextInput(type = "authorized_export", name = "Authorized export"))
            val model = service.trainModel(context.id)
            service.startModel(model.version)
            provides<RecommendationContextService> { service }
            provides<GroupEvaluator> { GroupEvaluator(mockk()) }
            provides<ArtifactPermissionEvaluator> { ArtifactPermissionEvaluator(mockk()) }
            val controller = RecommendationContextsMutationControllerProvider().get()
            val query = RecommendationContextsControllerProvider().get()
            fun token(scope: String): AuthenticationContext {
                val principal = ScopedAuthenticatedPrincipal(Principal(), emptyList(), listOf(scope), null, 119)
                return object : AuthenticationContext(null, null) {
                    override fun principal() = principal
                }
            }
            val content = "artifacts:ml:model/${model.contentModelName}:${model.version}:push"
            assertFailsWith<java.lang.SecurityException> {
                controller.modelExported(token(content), model.version, true)
            }
            assertFalse(checkNotNull(service.getModel(model.version)).exported)
            assertNull(service.getById(context.id)?.activeModelVersion)
            assertTrue(controller.modelExported(token("artifacts:push"), model.version, true))
            val exported = checkNotNull(service.getModel(model.version))
            assertTrue(exported.exported)
            assertTrue(exported.personalized)
            assertEquals(RecommendationTrainingStatus.RUNNING, exported.status)
            assertNull(service.getById(context.id)?.activeModelVersion)
            assertTrue(query.servingModels(token("artifacts:pull")).any { it.version == model.version })
            assertEquals(emptyList(), query.servingModels(token("analytics:execute")))
            service.completeModel(model.version)
            assertEquals(model.version, service.getById(context.id)?.activeModelVersion)
        }
    }

    @Test
    fun `unfinished and missing models cannot be exported completed pinned or activated`() {
        withDb {
            val context = service.add(RecommendationContextInput(type = "invalid_lifecycle", name = "Invalid lifecycle"))
            val version = service.trainModel(context.id).version
            assertFailsWith<IllegalArgumentException> { service.exportModel(version, false) }
            assertFailsWith<IllegalArgumentException> { service.completeModel(version) }
            assertFailsWith<IllegalArgumentException> { service.activateLoadedModel(version, context.selectionRevision) }
            assertFailsWith<IllegalArgumentException> { service.pinModel(context.id, version, true) }
            assertFailsWith<NoSuchElementException> { service.completeModel(Long.MAX_VALUE) }
            assertFailsWith<NoSuchElementException> { service.exportModel(Long.MAX_VALUE, false) }
            assertFailsWith<NoSuchElementException> { service.activateModel(UUID.random(), version) }
            assertFailsWith<NoSuchElementException> { service.pinModel(UUID.random(), version, true) }
            assertNull(service.getById(context.id)?.activeModelVersion)

            service.startModel(version)
            service.exportModel(version, false)
            service.completeModel(version)
            service.completeModel(version)
            assertEquals(version, service.getById(context.id)?.activeModelVersion)
        }
    }

    @Test
    fun `scheduled training captures a new version for every saved context`() {
        withDb {
            service.add(RecommendationContextInput(type = "scheduled", name = "Scheduled"))
            val contexts = service.getAll()
            val previous = contexts.associate { it.id to service.getModels(it.id).map { model -> model.version }.toSet() }
            service.trainAll()
            for (context in contexts) {
                val added = service.getModels(context.id).filter { it.version !in previous.getValue(context.id) }
                assertEquals(1, added.size)
                assertEquals(context.revision, added.single().revision)
                assertEquals(context.weights, added.single().context.weights)
                assertEquals(RecommendationTrainingStatus.QUEUED, added.single().status)
            }
        }
    }

    @Test
    fun `pending edits do not activate and an older job cannot undo manual selection`() {
        withDb {
            val created = service.add(RecommendationContextInput(type = "lifecycle", name = "Lifecycle"))
            val first = service.trainModel(created.id)
            assertEquals(RecommendationTrainingStatus.QUEUED, first.status)
            assertNull(service.getById(created.id)?.activeModelVersion)
            service.startModel(first.version)
            service.exportModel(first.version, false)
            assertNull(service.getById(created.id)?.activeModelVersion)
            service.completeModel(first.version)
            assertEquals(first.version, service.getById(created.id)?.activeModelVersion)

            service.edit(created.id, RecommendationContextInput(type = created.type, name = created.name,
                weights = RecommendationWeightsInput(defaultTypePreference = 0.9)))
            val second = service.trainModel(created.id)
            assertEquals(0.5, service.getModel(first.version)?.context?.weights?.defaultTypePreference)
            assertEquals(0.9, second.context.weights.defaultTypePreference)
            assertEquals(first.version, service.getById(created.id)?.activeModelVersion)
            service.startModel(second.version)
            service.exportModel(second.version, true)

            service.activateModel(created.id, first.version)
            val rollbackIntent = checkNotNull(service.getById(created.id)).selectionRevision
            service.activateLoadedModel(first.version, rollbackIntent)
            service.completeModel(second.version)
            assertEquals(first.version, service.getById(created.id)?.activeModelVersion)
            assertEquals(RecommendationTrainingStatus.COMPLETED, service.getModel(second.version)?.status)

            service.edit(created.id, RecommendationContextInput(type = created.type, name = created.name))
            val third = service.trainModel(created.id)
            service.startModel(third.version)
            service.exportModel(third.version, false)
            service.completeModel(third.version)
            assertEquals(third.version, service.getById(created.id)?.activeModelVersion)
            service.activateLoadedModel(first.version, rollbackIntent)
            assertEquals(third.version, service.getById(created.id)?.activeModelVersion)
        }
    }

    @Test
    fun `failed training keeps active model and history includes five previous plus pins`() {
        withDb {
            val context = service.add(RecommendationContextInput(type = "history", name = "History"))
            val first = service.trainModel(context.id)
            service.startModel(first.version)
            service.exportModel(first.version, false)
            service.completeModel(first.version)
            service.pinModel(context.id, first.version, true)
            repeat(7) {
                service.edit(context.id, RecommendationContextInput(type = context.type, name = context.name))
                val version = service.trainModel(context.id).version
                service.startModel(version)
                service.exportModel(version, false)
                service.completeModel(version)
            }
            val active = service.getById(context.id)?.activeModelVersion
            assertEquals(7, service.getModels(context.id).size)
            assertTrue(service.getModels(context.id).any { it.version == first.version && it.pinned })
            service.edit(context.id, RecommendationContextInput(type = context.type, name = context.name))
            val failed = service.trainModel(context.id).version
            service.startModel(failed)
            service.failModel(failed, "Export failed")
            assertEquals(active, service.getById(context.id)?.activeModelVersion)
            assertEquals("Export failed", service.getModel(failed)?.failure)
            assertFailsWith<IllegalArgumentException> { service.activateModel(context.id, failed) }
            service.pinModel(context.id, first.version, false)
            assertFalse(service.getModels(context.id).any { it.version == first.version })
            assertFailsWith<NoSuchElementException> { service.activateModel(context.id, first.version) }
        }
    }

    @Test
    fun `contexts persist independent weights and increment saved revisions`() {
        withDb {
            val first = service.add(RecommendationContextInput(
                type = "weights_first", name = "First",
                weights = RecommendationWeightsInput(
                    typePreferences = listOf(RecommendationTypePreferenceInput(" GUIDE ", 0.9)),
                ),
            ))
            val second = service.add(RecommendationContextInput(
                type = "weights_second", name = "Second",
                weights = RecommendationWeightsInput(
                    typePreferences = listOf(RecommendationTypePreferenceInput("guide", 0.1)),
                ),
            ))
            assertEquals(1, first.revision)
            assertEquals(0.9, service.getById(first.id)?.weights?.typePreferences?.single()?.weight)
            val edited = service.edit(first.id, RecommendationContextInput(
                type = first.type, name = first.name,
                weights = RecommendationWeightsInput(
                    defaultTypePreference = 0.0, personalization = 0.0,
                    typePreferences = listOf(RecommendationTypePreferenceInput("guide", 0.0)),
                ),
            ))
            assertEquals(2, edited.revision)
            assertEquals(0.0, service.getById(first.id)?.weights?.defaultTypePreference)
            assertEquals(0.0, service.getById(first.id)?.weights?.personalization)
            assertEquals(0.0, service.getById(first.id)?.weights?.typePreferences?.single()?.weight)
            assertEquals(0.1, service.getById(second.id)?.weights?.typePreferences?.single()?.weight)
            assertEquals(1, service.getById(second.id)?.revision)
        }
    }

    @Test
    fun `migration seeds the default context with the safe filter`() {
        withDb {
            val context = checkNotNull(service.getByType(" DEFAULT "))
            assertEquals(RecommendationContext.DEFAULT_TYPE, context.type)
            assertTrue("default" in classifier.classifyMetadata("bosca/v-document", null))
            assertFalse("default" in classifier.classifyMetadata("image/png", null))
            assertTrue("default" in classifier.classifyCollection("standard", null))
            assertEquals(context, service.getById(context.id))
            assertTrue(service.getAll().any { it.id == context.id })
        }
    }

    @Test
    fun `add edit and delete round trip a custom context filter`() {
        withDb {
            val created = service.add(
                RecommendationContextInput(
                    type = " IMAGE_PICKER ",
                    name = " Image picker ",
                    description = " Raw image selection ",
                    contentFilter = RecommendationContentFilterInput(
                        metadata = RecommendationMetadataFilterInput(
                            includedContentTypePrefixes = listOf("image/"),
                            excludedContentTypePrefixes = listOf("image/"),
                            includedAttributeTypes = listOf("hero"),
                            excludedAttributeTypes = listOf("hero"),
                        ),
                        collections = null,
                    ),
                ),
            )
            assertEquals("image_picker", created.type)
            assertEquals("Image picker", created.name)
            assertEquals(listOf("image/"), service.getByType("image_picker")?.contentFilter?.metadata?.includedContentTypePrefixes)
            assertEquals(listOf("hero"), created.contentFilter.metadata.includedAttributeTypes)
            assertNull(created.contentFilter.collections)
            val hero = buildJsonObject { put("type", " HERO ") }
            assertTrue("image_picker" in classifier.classifyMetadata(" IMAGE/JPEG; charset=binary ", hero))
            assertFalse("image_picker" in classifier.classifyMetadata("image/jpeg", null))
            assertFalse("image_picker" in classifier.classifyMetadata("video/mp4", hero))
            assertFalse("image_picker" in classifier.classifyCollection("standard", hero))

            val edited = service.edit(
                created.id,
                RecommendationContextInput(
                    type = "video_picker",
                    name = "Video picker",
                    contentFilter = RecommendationContentFilterInput(
                        metadata = RecommendationMetadataFilterInput(includedContentTypePrefixes = listOf("video/")),
                        collections = RecommendationCollectionFilterInput(
                            includedTypes = listOf("standard"),
                            includedAttributeTypes = listOf("series"),
                        ),
                    ),
                ),
            )
            assertEquals("video_picker", edited.type)
            assertEquals("Video picker", edited.name)
            assertEquals(listOf("video/"), edited.contentFilter.metadata.includedContentTypePrefixes)
            assertEquals(listOf("series"), edited.contentFilter.collections?.includedAttributeTypes)
            val series = buildJsonObject { put("type", "Series") }
            assertTrue("video_picker" in classifier.classifyMetadata("video/mp4", null))
            assertTrue("video_picker" in classifier.classifyCollection(" STANDARD ", series))
            assertFalse("video_picker" in classifier.classifyCollection("folder", series))
            assertFalse("video_picker" in classifier.classifyCollection("standard", null))

            service.delete(created.id)
            assertNull(service.getById(created.id))
            service.delete(created.id)
        }
        coVerify(exactly = 0) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `exclusion filters and missing facets classify in application code`() {
        withDb {
            service.add(
                RecommendationContextInput(
                    type = "exclusions",
                    name = "Exclusions",
                    contentFilter = RecommendationContentFilterInput(
                        metadata = RecommendationMetadataFilterInput(
                            includedContentTypePrefixes = listOf("", " "),
                            excludedContentTypePrefixes = listOf(" application/pdf ", ""),
                            excludedAttributeTypes = listOf(" hidden ", ""),
                        ),
                        collections = RecommendationCollectionFilterInput(
                            excludedTypes = listOf(" folder ", ""),
                            excludedAttributeTypes = listOf(" archive ", ""),
                        ),
                    ),
                ),
            )

            assertTrue("exclusions" in classifier.classifyMetadata(null, JsonPrimitive("not-an-object")))
            assertTrue(
                "exclusions" in classifier.classifyMetadata(
                    "text/plain",
                    buildJsonObject { put("type", 7) },
                ),
            )
            assertFalse("exclusions" in classifier.classifyMetadata("application/pdf", null))
            assertFalse(
                "exclusions" in classifier.classifyMetadata(
                    "text/plain",
                    buildJsonObject { put("type", "HIDDEN") },
                ),
            )
            assertTrue(
                "exclusions" in classifier.classifyCollection(
                    "standard",
                    buildJsonObject { put("type", 7) },
                ),
            )
            assertTrue("exclusions" in classifier.classifyCollection("   ", null))
            assertFalse("exclusions" in classifier.classifyCollection("folder", null))
            assertFalse(
                "exclusions" in classifier.classifyCollection(
                    "standard",
                    buildJsonObject { put("type", "ARCHIVE") },
                ),
            )
        }
    }

    @Test
    fun `default context must keep the default type and cannot be deleted`() {
        lateinit var defaultContext: RecommendationContext
        withDb { defaultContext = checkNotNull(service.getByType("default")) }

        withDb {
            val edited = service.edit(
                defaultContext.id,
                RecommendationContextInput(
                    type = " DEFAULT ",
                    name = defaultContext.name,
                    description = defaultContext.description,
                    contentFilter = RecommendationContentFilterInput(
                        metadata = RecommendationMetadataFilterInput(
                            includedContentTypePrefixes = defaultContext.contentFilter.metadata.includedContentTypePrefixes,
                            excludedContentTypePrefixes = defaultContext.contentFilter.metadata.excludedContentTypePrefixes,
                            includedAttributeTypes = defaultContext.contentFilter.metadata.includedAttributeTypes,
                            excludedAttributeTypes = defaultContext.contentFilter.metadata.excludedAttributeTypes,
                        ),
                        collections = defaultContext.contentFilter.collections?.let {
                            RecommendationCollectionFilterInput(
                                includedTypes = it.includedTypes,
                                excludedTypes = it.excludedTypes,
                                includedAttributeTypes = it.includedAttributeTypes,
                                excludedAttributeTypes = it.excludedAttributeTypes,
                            )
                        },
                    ),
                ),
            )
            assertEquals("default", edited.type)
        }

        assertFailsWith<IllegalArgumentException> {
            withDb {
                service.edit(defaultContext.id, RecommendationContextInput(type = "renamed", name = "Renamed"))
            }
        }
        assertFailsWith<IllegalArgumentException> { withDb { service.delete(defaultContext.id) } }
    }

    @Test
    fun `invalid context input and missing edits fail clearly`() {
        assertFailsWith<IllegalArgumentException> {
            withDb { service.add(RecommendationContextInput(type = "bad type", name = "Bad")) }
        }
        assertFailsWith<IllegalArgumentException> {
            withDb { service.add(RecommendationContextInput(type = "valid", name = " ")) }
        }
        lateinit var editable: RecommendationContext
        withDb {
            editable = service.add(RecommendationContextInput(type = "editable", name = "Editable"))
        }
        assertFailsWith<IllegalArgumentException> {
            withDb {
                service.edit(editable.id, RecommendationContextInput(type = editable.type, name = " "))
            }
        }
        assertFailsWith<NoSuchElementException> {
            withDb { service.edit(UUID.random(), RecommendationContextInput(type = "valid", name = "Valid")) }
        }
    }

    @Test
    fun `recompute pages metadata and collections through the same classifier`() {
        val repository = RecommendationContextRepositoryImpl()
        val metadataService = mockk<MetadataService>(relaxed = true)
        val collectionService = mockk<CollectionService>(relaxed = true)
        val metadataId = UUID.random()
        val collectionId = UUID.random()
        val metadata = mockk<Metadata> {
            every { id } returns metadataId
            every { contentType } returns "bosca/v-document"
            every { attributes } returns null
        }
        val collection = mockk<Collection> {
            every { id } returns collectionId
            every { type } returns CollectionType.STANDARD
            every { attributes } returns null
        }
        coEvery { metadataService.getAll(0, 500) } returns List(500) { metadata }
        coEvery { metadataService.getAll(500, 500) } returns emptyList()
        coEvery { collectionService.getAll(0, 500) } returns List(500) { collection }
        coEvery { collectionService.getAll(500, 500) } returns emptyList()
        val pagingClassifier = RecommendationContextClassifierImpl(repository, metadataService, collectionService)

        withDb { pagingClassifier.recompute() }

        coVerify(exactly = 500) {
            metadataService.setRecommendationContexts(metadataId, listOf(RecommendationContext.DEFAULT_TYPE))
        }
        coVerify(exactly = 500) {
            collectionService.setRecommendationContexts(collectionId, listOf(RecommendationContext.DEFAULT_TYPE))
        }
    }
}
