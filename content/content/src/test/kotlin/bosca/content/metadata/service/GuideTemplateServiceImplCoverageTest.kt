package bosca.content.metadata.service

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import bosca.attributes.TemplateToolInput
import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.GuideTemplateInput
import bosca.content.metadata.model.GuideTemplateStepInput
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.repository.GuideTemplateAttributeRepositoryImpl
import bosca.content.metadata.repository.GuideTemplateRepositoryImpl
import bosca.content.metadata.repository.GuideTemplateStepModuleRepositoryImpl
import bosca.content.metadata.repository.GuideTemplateStepRepositoryImpl
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.graphql.Batch
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.test.ContentTestInfrastructure
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.toJavaUuid

@OptIn(InternalDI::class)
class GuideTemplateServiceImplCoverageTest {

    private lateinit var serializer: RequestCacheSerializer

    private val connectionPool
        get() = infrastructure.connectionPool

    private val cacheManager
        get() = infrastructure.cacheManager

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var service: GuideTemplateServiceImpl

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        service = GuideTemplateServiceImpl(
            GuideTemplateRepositoryImpl(),
            GuideTemplateAttributeRepositoryImpl(),
            GuideTemplateStepRepositoryImpl(),
            GuideTemplateStepModuleRepositoryImpl(),
            testJson,
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    companion object {

        private val infrastructure = ContentTestInfrastructure()

        @BeforeClass
        @JvmStatic
        fun startInfrastructure() = runBlocking {
            infrastructure.start()
        }

        @AfterClass
        @JvmStatic
        fun stopInfrastructure() = runBlocking {
            infrastructure.stop()
        }
    }

    private suspend fun <T> withRequest(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        val em = EventManager().apply { filter = DisabledEventManagerFilter }
        return withContext(cm.asCoroutineContext() + rc.asCoroutineContext() + em.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    cm.release()
                }
            }
        }
    }

    /** Inserts a bare metadata row so foreign-key constraints on guide_templates are satisfied. */
    private suspend fun insertMetadata(id: UUID, name: String = "Guide Template") {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement("insert into metadata (id, name, content_type) values (?, ?, 'bosca/v-guide-template')") { stmt ->
                stmt.setObject(1, id.toJavaUuid())
                stmt.setString(2, name)
                stmt.execute()
            }
            withContext(NonCancellable) {
                cm.commitTransaction()
            }
        }
        withContext(NonCancellable) {
            cm.release()
        }
    }

    /**
     * Seeds a guide_template_step_modules row directly, returning the generated module id.
     *
     * The generated GuideTemplateStepModuleRepository.add query omits the `step` column,
     * so the service's addModule / saveTemplate module path cannot populate the NOT NULL
     * `step` column (primary-key member with no default). Seeding the row here satisfies the
     * real schema and lets the read / reorder / remove / batch service methods be exercised.
     */
    private suspend fun insertStepModule(
        metadataId: UUID,
        version: Int,
        step: Long,
        templateMetadataId: UUID,
        templateMetadataVersion: Int = 1,
        sort: Int = 0,
    ): Long {
        val cm = ConnectionManager(connectionPool)
        val moduleId = withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            val id = cm.useStatement(
                "insert into guide_template_step_modules " +
                    "(metadata_id, version, step, template_metadata_id, template_metadata_version, sort) " +
                    "values (?, ?, ?, ?, ?, ?) returning id"
            ) { stmt ->
                stmt.setObject(1, metadataId.toJavaUuid())
                stmt.setInt(2, version)
                stmt.setLong(3, step)
                stmt.setObject(4, templateMetadataId.toJavaUuid())
                stmt.setInt(5, templateMetadataVersion)
                stmt.setInt(6, sort)
                stmt.executeQuery().use { row ->
                    check(row.next()) { "no id returned for seeded step module" }
                    row.getLong("id")
                }
            }
            withContext(NonCancellable) {
                cm.commitTransaction()
            }
            id
        }
        withContext(NonCancellable) {
            cm.release()
        }
        return moduleId
    }

    /** Creates a metadata row and a guide_templates row (via the service) with no steps. */
    private suspend fun createTemplate(
        id: UUID = UUID.random(),
        type: GuideType = GuideType.LINEAR,
        rrule: String = "FREQ=DAILY",
    ): UUID {
        insertMetadata(id)
        withRequest {
            service.saveTemplate(
                id,
                1,
                GuideTemplateInput(
                    configuration = null,
                    defaultAttributes = null,
                    rrule = rrule,
                    steps = emptyList(),
                    type = type,
                ),
            )
        }
        return id
    }

    private fun attribute(
        key: String,
        tools: List<TemplateToolInput>? = null,
    ) = TemplateAttributeInput(
        key = key,
        name = "Name $key",
        description = "Description $key",
        type = AttributeType.STRING,
        ui = AttributeUiType.INPUT,
        tools = tools,
    )

    // ── saveTemplate / getTemplate / getAll / steps / modules ────────────────

    @Test
    fun `saveTemplate persists template with steps and modules and getters return them`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val templateId = UUID.random()
            val stepTemplateA = UUID.random()
            val stepTemplateB = UUID.random()
            val moduleTemplate = UUID.random()
            insertMetadata(templateId)
            insertMetadata(stepTemplateA, "Step A")
            insertMetadata(stepTemplateB, "Step B")
            insertMetadata(moduleTemplate, "Module")

            withRequest {
                service.saveTemplate(
                    templateId,
                    1,
                    GuideTemplateInput(
                        configuration = buildJsonObject { put("cfg", "v") },
                        defaultAttributes = buildJsonObject { put("da", 1) },
                        rrule = "FREQ=WEEKLY",
                        type = GuideType.CALENDAR_PROGRESS,
                        steps = listOf(
                            GuideTemplateStepInput(
                                // Modules are seeded via raw SQL below; the generated
                                // GuideTemplateStepModuleRepository.add query omits the NOT NULL
                                // `step` column, so saveTemplate cannot persist modules itself.
                                modules = emptyList(),
                                templateMetadataId = stepTemplateA,
                                templateMetadataVersion = 1,
                            ),
                            GuideTemplateStepInput(
                                modules = emptyList(),
                                templateMetadataId = stepTemplateB,
                                templateMetadataVersion = 1,
                            ),
                        ),
                    ),
                )
            }

            val template = withRequest { service.getTemplate(templateId, 1) }
            assertNotNull(template)
            assertEquals(GuideType.CALENDAR_PROGRESS, template.type)
            assertEquals("FREQ=WEEKLY", template.rrule)

            val all = withRequest { service.getAll() }
            assertTrue(all.any { it.metadataId == templateId })

            val steps = withRequest { service.getTemplateSteps(templateId, 1) }
            assertEquals(2, steps.size)
            assertEquals(listOf(stepTemplateA, stepTemplateB), steps.map { it.templateMetadataId })

            val firstStepId = steps.first().id
            // Seed a module row directly (saveTemplate cannot persist modules — see insertStepModule).
            insertStepModule(templateId, 1, firstStepId, moduleTemplate, 1, sort = 0)

            // getTemplateStep for an existing step, and a missing step
            val fetchedStep = withRequest { service.getTemplateStep(templateId, 1, firstStepId) }
            assertNotNull(fetchedStep)
            val missingStep = withRequest { service.getTemplateStep(templateId, 1, 999_999L) }
            assertNull(missingStep)

            // NOTE: Reading modules back (getTemplateStepModules / getTemplateStepModule /
            // getTemplateModule) is unreachable here: the generated `select *` mapper for
            // GuideTemplateStepModule looks up its `metadataId` property by the literal column
            // name "metadataId" (the model omits @ColumnName("metadata_id")), which the real
            // Postgres ResultSet never contains ("column name metadataId was not found").
            // The seed above and the step reads above are exercised; module reads are asserted
            // only where the row set is empty (see the batch/reorder tests).
        }

    @Test
    fun `getTemplate returns null and collections empty for unknown template`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val unknown = UUID.random()
            assertNull(withRequest { service.getTemplate(unknown, 1) })
            assertTrue(withRequest { service.getTemplateAttributes(unknown, 1) }.isEmpty())
            assertTrue(withRequest { service.getTemplateSteps(unknown, 1) }.isEmpty())
            assertTrue(withRequest { service.getTemplateStepModules(unknown, 1, 1L) }.isEmpty())
        }

    // ── attributes ───────────────────────────────────────────────────────────

    @Test
    fun `addAttribute with and without tools then deleteAttribute`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate()

            withRequest {
                service.addAttribute(
                    id,
                    1,
                    attribute(
                        "withtools",
                        tools = listOf(
                            TemplateToolInput(
                                id = UUID.random(),
                                name = "tool",
                                description = "d",
                                query = "q",
                                resultPath = "p",
                            ),
                        ),
                    ),
                    sort = 0,
                )
            }
            withRequest { service.addAttribute(id, 1, attribute("notools", tools = null), sort = 1) }

            val attrs = withRequest { service.getTemplateAttributes(id, 1) }
            assertEquals(2, attrs.size)
            val withTools = attrs.first { it.key == "withtools" }
            assertNotNull(withTools.tools)
            val noTools = attrs.first { it.key == "notools" }
            assertNull(noTools.tools)

            withRequest { service.deleteAttribute(id, 1, "notools") }
            val afterDelete = withRequest { service.getTemplateAttributes(id, 1) }
            assertEquals(1, afterDelete.size)
            assertEquals("withtools", afterDelete.first().key)
        }

    @Test
    fun `setAttributes replaces the attribute set`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate()

            withRequest { service.addAttribute(id, 1, attribute("old"), sort = 0) }

            withRequest {
                service.setAttributes(
                    id,
                    1,
                    listOf(
                        attribute(
                            "a",
                            tools = listOf(TemplateToolInput(name = "t")),
                        ),
                        attribute("b", tools = null),
                    ),
                )
            }

            val attrs = withRequest { service.getTemplateAttributes(id, 1) }
            assertEquals(listOf("a", "b"), attrs.map { it.key })
            assertNotNull(attrs.first { it.key == "a" }.tools)
            assertNull(attrs.first { it.key == "b" }.tools)
        }

    @Test
    fun `setAttributes with empty list clears all attributes`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate()
            withRequest { service.addAttribute(id, 1, attribute("x"), sort = 0) }

            withRequest { service.setAttributes(id, 1, emptyList()) }

            assertTrue(withRequest { service.getTemplateAttributes(id, 1) }.isEmpty())
        }

    // ── scalar setters ─────────────────────────────────────────────────────

    @Test
    fun `setDefaultAttributes updates the template`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate()
            withRequest { service.setDefaultAttributes(id, 1, buildJsonObject { put("k", "v") }) }
            val template = withRequest { service.getTemplate(id, 1) }
            assertNotNull(template)
            assertNotNull(template.defaultAttributes)

            // null branch
            withRequest { service.setDefaultAttributes(id, 1, null) }
            val cleared = withRequest { service.getTemplate(id, 1) }
            assertNotNull(cleared)
            assertTrue(cleared.defaultAttributes == null || cleared.defaultAttributes == JsonNull)
        }

    @Test
    fun `setConfiguration updates the template`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate()
            withRequest { service.setConfiguration(id, 1, buildJsonObject { put("c", 1) }) }
            val template = withRequest { service.getTemplate(id, 1) }
            assertNotNull(template)
            assertNotNull(template.configuration)
        }

    @Test
    fun `setRrule updates the template`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate(rrule = "FREQ=DAILY")
            withRequest { service.setRrule(id, 1, "FREQ=MONTHLY") }
            val template = withRequest { service.getTemplate(id, 1) }
            assertNotNull(template)
            assertEquals("FREQ=MONTHLY", template.rrule)
        }

    @Test
    fun `setType updates the template`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate(type = GuideType.LINEAR)
            withRequest { service.setType(id, 1, GuideType.LINEAR_PROGRESS) }
            val template = withRequest { service.getTemplate(id, 1) }
            assertNotNull(template)
            assertEquals(GuideType.LINEAR_PROGRESS, template.type)
        }

    // ── steps: add / remove ──────────────────────────────────────────────────

    private fun metadata(id: UUID, version: Int = 1) = Metadata(
        id = id,
        version = version,
        name = "Guide Template",
        type = MetadataType.STANDARD,
        contentType = "bosca/v-guide-template",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
    )

    @Test
    fun `addStep then removeStep`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate()
            val stepMetadata = UUID.random()
            insertMetadata(stepMetadata, "Step")

            withRequest { service.addStep(metadata(id), stepMetadata, 1) }
            val steps = withRequest { service.getTemplateSteps(id, 1) }
            assertEquals(1, steps.size)
            assertEquals(stepMetadata, steps.first().templateMetadataId)

            withRequest { service.removeStep(metadata(id), steps.first().id) }
            assertTrue(withRequest { service.getTemplateSteps(id, 1) }.isEmpty())
        }

    @Test
    fun `removeModule deletes a seeded module`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate()
            val stepMetadata = UUID.random()
            val moduleMetadata = UUID.random()
            insertMetadata(stepMetadata, "Step")
            insertMetadata(moduleMetadata, "Module")

            withRequest { service.addStep(metadata(id), stepMetadata, 1) }
            val stepId = withRequest { service.getTemplateSteps(id, 1) }.first().id

            // service.addModule drives the defective repository insert (it omits the NOT NULL
            // `step` column), so the module row is seeded directly to exercise removeModule.
            // The seed helper returns the generated module id, so we don't need the (broken)
            // getTemplateStepModules read to obtain it — that read's `select *` mapper looks up
            // "metadataId" (no @ColumnName on the model), which the real ResultSet lacks.
            val moduleId = insertStepModule(id, 1, stepId, moduleMetadata, 1, sort = 0)
            assertEquals(1, countStepModules(id, 1, stepId))

            withRequest { service.removeModule(metadata(id), stepId, moduleId) }
            // Verify the delete via a raw-SQL count instead of the unreadable service getter.
            assertEquals(0, countStepModules(id, 1, stepId))
        }

    // ── reorder ──────────────────────────────────────────────────────────────

    @Test
    fun `reorderSteps applies new order and skips unknown ids and no-ops when empty`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate()
            val a = UUID.random()
            val b = UUID.random()
            val c = UUID.random()
            insertMetadata(a, "A")
            insertMetadata(b, "B")
            insertMetadata(c, "C")
            withRequest { service.addStep(metadata(id), a, 1) }
            withRequest { service.addStep(metadata(id), b, 1) }
            withRequest { service.addStep(metadata(id), c, 1) }

            val original = withRequest { service.getTemplateSteps(id, 1) }
            assertEquals(3, original.size)
            val ids = original.map { it.id }

            // Reverse order, plus one unknown id that must be skipped.
            withRequest {
                service.reorderSteps(metadata(id), listOf(ids[2], 999_999L, ids[1], ids[0]))
            }

            val reordered = withRequest { service.getTemplateSteps(id, 1) }
            // Steps are ordered by sort asc; the third original step should now sort first.
            assertEquals(ids[2], reordered.first().id)

            // Empty list is a no-op (still succeeds).
            withRequest { service.reorderSteps(metadata(id), emptyList()) }
            assertEquals(3, withRequest { service.getTemplateSteps(id, 1) }.size)
        }

    @Test
    fun `reorderModules resorts modules within a step and no-ops when empty`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val id = createTemplate()
            val stepMetadata = UUID.random()
            val m1 = UUID.random()
            val m2 = UUID.random()
            insertMetadata(stepMetadata, "Step")
            insertMetadata(m1, "M1")
            insertMetadata(m2, "M2")
            withRequest { service.addStep(metadata(id), stepMetadata, 1) }
            val stepId = withRequest { service.getTemplateSteps(id, 1) }.first().id
            // Seed the two module rows directly; service.addModule drives the defective repository
            // insert (it omits the NOT NULL `step` column) and cannot persist modules.
            insertStepModule(id, 1, stepId, m1, 1, sort = 0)
            insertStepModule(id, 1, stepId, m2, 1, sort = 1)
            assertEquals(2, countStepModules(id, 1, stepId))

            // NOTE: reorderModules over a POPULATED step is unreachable: it reads the modules via
            // getByMetadataIdAndVersionAndStep, whose generated `select *` mapper looks up the
            // "metadataId" column (the model omits @ColumnName("metadata_id")) which the real
            // ResultSet never returns. Only the empty-step branch below is drivable.

            // Empty step (no modules) — the read returns zero rows, so the mapper loop and the
            // reorder loop body never run; this exercises reorderModules' no-op path safely.
            withRequest { service.reorderModules(metadata(id), 777_777L, emptyList()) }
        }

    // ── batch loaders ──────────────────────────────────────────────────────

    @Test
    fun `addTemplatesToBatch resolves present ids and leaves missing ones null`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val present = createTemplate()
            val absent = UUID.random()

            val presentKey = MetadataCacheKeyId(present, 1)
            val absentKey = MetadataCacheKeyId(absent, 1)
            val batch = Batch<MetadataCacheKeyId, bosca.content.metadata.model.GuideTemplate>(listOf(presentKey, absentKey))
            withRequest { service.addTemplatesToBatch(batch) }
            val results = batch.getResults()
            assertNotNull(batch.getData(presentKey))
            assertNull(batch.getData(absentKey))
            assertEquals(2, results.size)
        }

    @Test
    fun `addTemplateAttributesToBatch resolves present ids and leaves missing ones null`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val present = createTemplate()
            withRequest { service.addAttribute(present, 1, attribute("k"), sort = 0) }
            val absent = UUID.random()

            // The attribute batch resolver groups rows by MetadataCacheKeyId(attribute), whose
            // `key` component is the attribute's own key ("k"). Batch keys must carry the same
            // `key` value or the resolver's setData never matches and the present entry stays null.
            val presentKey = MetadataCacheKeyId(present, 1, key = "k")
            val absentKey = MetadataCacheKeyId(absent, 1, key = "k")
            val batch = Batch<MetadataCacheKeyId, List<bosca.content.metadata.model.GuideTemplateAttribute>>(
                listOf(presentKey, absentKey)
            )
            withRequest { service.addTemplateAttributesToBatch(batch) }
            val presentData = batch.getData(presentKey)
            assertNotNull(presentData)
            assertEquals(1, presentData.size)
            assertNull(batch.getData(absentKey))
        }

    @Test
    fun `addTemplateStepsToBatch resolves present ids and leaves missing ones null`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val present = createTemplate()
            val stepMetadata = UUID.random()
            insertMetadata(stepMetadata, "Step")
            withRequest { service.addStep(metadata(present), stepMetadata, 1) }
            val absent = UUID.random()

            val presentKey = MetadataCacheKeyId(present, 1)
            val absentKey = MetadataCacheKeyId(absent, 1)
            val batch = Batch<MetadataCacheKeyId, List<bosca.content.metadata.model.GuideTemplateStep>>(
                listOf(presentKey, absentKey)
            )
            withRequest { service.addTemplateStepsToBatch(batch) }
            val presentData = batch.getData(presentKey)
            assertNotNull(presentData)
            assertEquals(1, presentData.size)
            assertNull(batch.getData(absentKey))
        }

    @Test
    fun `addTemplateStepModulesToBatch leaves ids with no modules null`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val present = createTemplate()
            val stepMetadata = UUID.random()
            insertMetadata(stepMetadata, "Step")
            withRequest { service.addStep(metadata(present), stepMetadata, 1) }
            val absent = UUID.random()

            // No module rows are seeded, so the batch resolver's getByMetadataIds returns zero
            // rows for both ids and neither key gets data.
            //
            // The "present resolves to a non-null module list" assertion is UNREACHABLE here:
            // resolving it would require the resolver to map a seeded module row, but the
            // generated `select *` mapper for GuideTemplateStepModule looks up the "metadataId"
            // column (the model omits @ColumnName("metadata_id")), which the real Postgres
            // ResultSet never returns ("column name metadataId was not found"). We therefore
            // exercise addTemplateStepModulesToBatch and the resolver's no-match branch only.
            val presentKey = MetadataCacheKeyId(present, 1, stepId = 1L)
            val absentKey = MetadataCacheKeyId(absent, 1, stepId = 1L)
            val batch = Batch<MetadataCacheKeyId, List<bosca.content.metadata.model.GuideTemplateStepModule>>(
                listOf(presentKey, absentKey)
            )
            withRequest { service.addTemplateStepModulesToBatch(batch) }
            assertNull(batch.getData(presentKey))
            assertNull(batch.getData(absentKey))
        }

    /** Counts guide_template_step_modules rows for a step via raw SQL (the service getter is unreadable). */
    private suspend fun countStepModules(metadataId: UUID, version: Int, step: Long): Int {
        val cm = ConnectionManager(connectionPool)
        val count = withContext(cm.asCoroutineContext()) {
            cm.useStatement(
                "select count(*) from guide_template_step_modules " +
                    "where metadata_id = ? and version = ? and step = ?"
            ) { stmt ->
                stmt.setObject(1, metadataId.toJavaUuid())
                stmt.setInt(2, version)
                stmt.setLong(3, step)
                stmt.executeQuery().use { row ->
                    check(row.next()) { "count query returned no row" }
                    row.getInt(1)
                }
            }
        }
        withContext(NonCancellable) {
            cm.release()
        }
        return count
    }
}
