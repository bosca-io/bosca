@file:OptIn(InternalDI::class)

package bosca.content.transformations.pipeline

import bosca.category.service.CategoryService
import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.pipeline.executeForTestValue
import bosca.content.transformations.CollectionToSearchDocument
import bosca.content.transformations.MetadataToSearchDocument
import bosca.content.transformations.ProfileToSearchDocument
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.search.IndexStorageSystem
import bosca.search.model.CollectionSearchContext
import bosca.search.model.MetadataSearchContext
import bosca.search.model.ProfileSearchContext
import bosca.search.model.SearchTransformConfiguration
import bosca.search.model.SearchTransformExpressions
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BuildSearchDocumentNodesTest {

    private val metadataTransform = mockk<MetadataToSearchDocument>()
    private val collectionTransform = mockk<CollectionToSearchDocument>()
    private val profileTransform = mockk<ProfileToSearchDocument>()
    private val collectionService = mockk<CollectionService>()
    private val categoryService = mockk<CategoryService>()
    private val configurationService = mockk<ConfigurationService>()

    private val json = Json
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<MetadataToSearchDocument> { metadataTransform }
        provides<CollectionToSearchDocument> { collectionTransform }
        provides<ProfileToSearchDocument> { profileTransform }
        provides<CollectionService> { collectionService }
        provides<CategoryService> { categoryService }
        provides<ConfigurationService> { configurationService }
        // Default: no `search` configuration present (blank-expression tests opt in explicitly).
        coEvery { configurationService.getByKey("search") } returns null
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("in" to value))

    private fun metadata(name: String, workflowStateId: String, public: Boolean = true) = Metadata(
        id = UUID.random(),
        name = name,
        type = MetadataType.STANDARD,
        languageTag = "en",
        contentType = "text/plain",
        contentLength = 10,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        public = public,
        workflowStateId = workflowStateId,
    )

    private fun metadataContext(metadata: Metadata) = MetadataSearchContext(
        metadata = metadata,
        content = "body",
        relationships = emptyMap(),
        categories = emptyList(),
        collections = emptyMap(),
        attributes = JsonObject(emptyMap()),
        slug = "slug",
        bibleBooks = null,
    )

    private fun collection(name: String, workflowStateId: String, public: Boolean = true) = Collection(
        id = UUID.random(),
        name = name,
        languageTag = "en",
        public = public,
        workflowStateId = workflowStateId,
    )

    private fun profile(name: String, visibility: ProfileVisibility) = Profile(
        id = UUID.random(),
        type = ProfileType.GENERIC,
        name = name,
        visibility = visibility,
    )

    // ── Metadata ────────────────────────────────────────────────────────────────

    @Test
    fun `build metadata applies the node expression to the built context`() = runTest {
        val md = metadata("Doc", "published")
        coEvery { metadataTransform.toContext(any(), md) } returns metadataContext(md)

        val node = BuildMetadataSearchDocumentNode(id = "n1", expression = "{ \"n\": metadata.name, \"c\": content }")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))

        val obj = out.value as JsonObject
        assertEquals("Doc", (obj["n"] as JsonPrimitive).content)
        assertEquals("body", (obj["c"] as JsonPrimitive).content)
    }

    @Test
    fun `build metadata falls back to the configured expression when its own is blank`() = runTest {
        val md = metadata("Doc", "published")
        coEvery { metadataTransform.toContext(any(), md) } returns metadataContext(md)
        val cfgId = UUID.random()
        coEvery { configurationService.getByKey("search") } returns Configuration(cfgId, "search", "", false)
        coEvery { configurationService.getValue(cfgId) } returns json.encodeToJsonElement(
            SearchTransformConfiguration(SearchTransformExpressions(metadata = "{ \"viaConfig\": metadata.name }")),
        )

        val node = BuildMetadataSearchDocumentNode(id = "n1")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))

        assertEquals("Doc", ((out.value as JsonObject)["viaConfig"] as JsonPrimitive).content)
    }

    @Test
    fun `build metadata with no expression and no config returns the raw context`() = runTest {
        val md = metadata("Doc", "published")
        coEvery { metadataTransform.toContext(any(), md) } returns metadataContext(md)

        val node = BuildMetadataSearchDocumentNode(id = "n1")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))

        assertTrue((out.value as JsonObject).containsKey("metadata"), "raw context carries the metadata")
    }

    @Test
    fun `build metadata emits a removal signal when not visible for the default index`() = runTest {
        val md = metadata("Doc", "draft") // not published → not visible on the default index
        val node = BuildMetadataSearchDocumentNode(id = "n1")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))

        val obj = out.value as JsonObject
        assertEquals(SearchDocumentPipeline.ACTION_DELETE, (obj[SearchDocumentPipeline.ACTION_FIELD] as JsonPrimitive).content)
        assertEquals(md.id.toString(), (obj[SearchDocumentPipeline.CONTENT_ID_FIELD] as JsonPrimitive).content)
    }

    @Test
    fun `build metadata indexes an unpublished entity when the published requirement is off`() = runTest {
        val md = metadata("Doc", "draft")
        coEvery { metadataTransform.toContext(any(), md) } returns metadataContext(md)

        // The admin-index branch turns the public/published/searchable requirements off — no special-casing.
        val node = BuildMetadataSearchDocumentNode(
            id = "n1",
            expression = "{ \"n\": metadata.name }",
            index = SearchDocumentPipeline.ADMIN_INDEX,
            requirePublic = false,
            requirePublished = false,
            requireSearchable = false,
        )
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))

        assertEquals("Doc", ((out.value as JsonObject)["n"] as JsonPrimitive).content)
    }

    @Test
    fun `build metadata removes when the extra constraint fails`() = runTest {
        val md = metadata("Doc", "published")

        val node = BuildMetadataSearchDocumentNode(id = "n1", indexWhen = "name = 'Other'")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))

        val obj = out.value as JsonObject
        assertEquals(SearchDocumentPipeline.ACTION_DELETE, (obj[SearchDocumentPipeline.ACTION_FIELD] as JsonPrimitive).content)
        assertEquals(md.id.toString(), (obj[SearchDocumentPipeline.CONTENT_ID_FIELD] as JsonPrimitive).content)
    }

    @Test
    fun `build metadata indexes when the extra constraint passes`() = runTest {
        val md = metadata("Doc", "published")
        coEvery { metadataTransform.toContext(any(), md) } returns metadataContext(md)

        val node = BuildMetadataSearchDocumentNode(id = "n1", expression = "{ \"n\": metadata.name }", indexWhen = "name = 'Doc'")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))

        assertEquals("Doc", ((out.value as JsonObject)["n"] as JsonPrimitive).content)
    }

    // ── Collection ──────────────────────────────────────────────────────────────

    @Test
    fun `build collection applies the node expression to the built context`() = runTest {
        val col = collection("Col", "published")
        coEvery { collectionService.getCategoryIds(col.id) } returns emptyList()
        coEvery { collectionTransform.toContext(any(), col, any()) } returns CollectionSearchContext(
            collection = col,
            categories = emptyList(),
            variants = emptyList(),
            slug = "slug",
            isAdmin = false,
        )

        val node = BuildCollectionSearchDocumentNode(id = "n1", expression = "{ \"n\": collection.name }")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(col, Collection.serializer())))

        assertEquals("Col", ((out.value as JsonObject)["n"] as JsonPrimitive).content)
    }

    @Test
    fun `build collection emits a removal signal when not visible`() = runTest {
        val col = collection("Col", "draft", public = false)
        val node = BuildCollectionSearchDocumentNode(id = "n1")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(col, Collection.serializer())))

        val obj = out.value as JsonObject
        assertEquals(SearchDocumentPipeline.ACTION_DELETE, (obj[SearchDocumentPipeline.ACTION_FIELD] as JsonPrimitive).content)
        assertEquals(col.id.toString(), (obj[SearchDocumentPipeline.CONTENT_ID_FIELD] as JsonPrimitive).content)
    }

    // ── Profile ─────────────────────────────────────────────────────────────────

    @Test
    fun `build profile applies the node expression to the built context`() = runTest {
        val pro = profile("Pro", ProfileVisibility.PUBLIC)
        coEvery { profileTransform.toContext(any(), pro) } returns ProfileSearchContext(
            storage = IndexStorageSystem(name = SearchDocumentPipeline.DEFAULT_INDEX),
            profile = pro,
            contentType = "bosca/v-profile-generic",
            organization = null,
            organizations = emptyList(),
            memberCount = 0,
            attributes = emptyList(),
            slug = "slug",
        )

        val node = BuildProfileSearchDocumentNode(id = "n1", expression = "{ \"n\": profile.name }")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(pro, Profile.serializer())))

        assertEquals("Pro", ((out.value as JsonObject)["n"] as JsonPrimitive).content)
    }

    @Test
    fun `build profile emits a removal signal when not public`() = runTest {
        val pro = profile("Pro", ProfileVisibility.USER) // not PUBLIC → not visible on the profile index
        val node = BuildProfileSearchDocumentNode(id = "n1")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(pro, Profile.serializer())))

        val obj = out.value as JsonObject
        assertEquals(SearchDocumentPipeline.ACTION_DELETE, (obj[SearchDocumentPipeline.ACTION_FIELD] as JsonPrimitive).content)
        assertEquals(pro.id.toString(), (obj[SearchDocumentPipeline.CONTENT_ID_FIELD] as JsonPrimitive).content)
    }

    // ── wrong-input handling ──────────────────────────────────────────────────────

    @Test
    fun `build nodes fail clearly on a non-entity input`() = runTest {
        // The generated deserialize bridges through each entity's serializer, so a non-entity value
        // fails decoding.
        val bad = PipelineValue.of("nope", String.serializer())
        assertFailsWith<SerializationException> {
            BuildMetadataSearchDocumentNode(id = "n1", name = "Build it").executeForTestValue(context, inputs(bad))
        }
        assertFailsWith<SerializationException> { BuildCollectionSearchDocumentNode(id = "n1", name = "Build col").executeForTestValue(context, inputs(bad)) }
        assertFailsWith<SerializationException> { BuildProfileSearchDocumentNode(id = "n1", name = "Build pro").executeForTestValue(context, inputs(bad)) }
    }

    @Test
    fun `build nodes require an input - the generated required-input message names the port`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            BuildMetadataSearchDocumentNode(id = "n1").executeForTestValue(context, NodeInputs(emptyMap()))
        }
        assertTrue("required input 'in'" in (e.message ?: ""), e.message ?: "")
        assertFailsWith<IllegalStateException> { BuildCollectionSearchDocumentNode(id = "n1").executeForTestValue(context, NodeInputs(emptyMap())) }
        assertFailsWith<IllegalStateException> { BuildProfileSearchDocumentNode(id = "n1").executeForTestValue(context, NodeInputs(emptyMap())) }
    }

    @Test
    fun `build metadata falls back to the raw context when the search config has no value`() = runTest {
        val md = metadata("Doc", "published")
        coEvery { metadataTransform.toContext(any(), md) } returns metadataContext(md)
        val cfgId = UUID.random()
        coEvery { configurationService.getByKey("search") } returns Configuration(cfgId, "search", "", false)
        coEvery { configurationService.getValue(cfgId) } returns null // key present, no value → null → raw context

        val out = BuildMetadataSearchDocumentNode(id = "n1").executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))
        assertTrue((out.value as JsonObject).containsKey("metadata"))
    }

    @Test
    fun `build metadata treats a JsonNull config value as no expression`() = runTest {
        val md = metadata("Doc", "published")
        coEvery { metadataTransform.toContext(any(), md) } returns metadataContext(md)
        val cfgId = UUID.random()
        coEvery { configurationService.getByKey("search") } returns Configuration(cfgId, "search", "", false)
        coEvery { configurationService.getValue(cfgId) } returns JsonNull // explicit null value → raw context

        val out = BuildMetadataSearchDocumentNode(id = "n1").executeForTestValue(context, inputs(PipelineValue.of(md, Metadata.serializer())))
        assertTrue((out.value as JsonObject).containsKey("metadata"))
    }

    @Test
    fun `build collection removes when its extra constraint fails`() = runTest {
        val col = collection("Col", "published")
        coEvery { collectionService.getCategoryIds(col.id) } returns emptyList()
        val node = BuildCollectionSearchDocumentNode(id = "n1", indexWhen = "name = 'Other'")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(col, Collection.serializer())))
        assertEquals(SearchDocumentPipeline.ACTION_DELETE, ((out.value as JsonObject)[SearchDocumentPipeline.ACTION_FIELD] as JsonPrimitive).content)
    }

    @Test
    fun `build profile removes when its extra constraint fails`() = runTest {
        val pro = profile("Pro", ProfileVisibility.PUBLIC)
        val node = BuildProfileSearchDocumentNode(id = "n1", indexWhen = "name = 'Other'")
        val out = node.executeForTestValue(context, inputs(PipelineValue.of(pro, Profile.serializer())))
        assertEquals(SearchDocumentPipeline.ACTION_DELETE, ((out.value as JsonObject)[SearchDocumentPipeline.ACTION_FIELD] as JsonPrimitive).content)
    }

    @Test
    fun `build nodes reject malformed serialized graphs`() {
        // missing required `id` and unknown fields both throw — exercises the serializer's throw-on-missing
        // and throw-on-unknown-key arms.
        assertFailsWith<SerializationException> { json.decodeFromString(BuildMetadataSearchDocumentNode.serializer(), "{}") }
        assertFailsWith<SerializationException> { json.decodeFromString(BuildMetadataSearchDocumentNode.serializer(), """{"id":"x","bogus":1}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(BuildCollectionSearchDocumentNode.serializer(), "{}") }
        assertFailsWith<SerializationException> { json.decodeFromString(BuildCollectionSearchDocumentNode.serializer(), """{"id":"x","bogus":1}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(BuildProfileSearchDocumentNode.serializer(), "{}") }
        assertFailsWith<SerializationException> { json.decodeFromString(BuildProfileSearchDocumentNode.serializer(), """{"id":"x","bogus":1}""") }
    }

    // ── serialization ─────────────────────────────────────────────────────────────

    /** Writes defaults too, so write$Self's shouldEncodeElementDefault arm is exercised. */
    private val jsonWithDefaults = Json { encodeDefaults = true }

    /** encode → decode → re-encode; equal JSON proves every setting survives, under both encode modes. */
    private fun <T> assertRoundTrips(serializer: KSerializer<T>, node: T) {
        for (j in listOf(json, jsonWithDefaults)) {
            val encoded = j.encodeToString(serializer, node)
            val reEncoded = j.encodeToString(serializer, j.decodeFromString(serializer, encoded))
            assertEquals(encoded, reEncoded, "a setting did not survive the round-trip")
        }
    }

    @Test
    fun `every setting on each build node survives a round-trip`() {
        // All settings non-default → all are written, so the round-trip exercises the serializer's
        // value-provided path (not only the default path) and proves no setting is silently dropped.
        assertRoundTrips(
            BuildMetadataSearchDocumentNode.serializer(),
            BuildMetadataSearchDocumentNode(
                id = "n1", name = "N", description = "D", expression = "x", index = "Admin Search Index",
                requirePublic = false, requirePublished = false, requireSearchable = false, excludeDeleted = false,
                indexWhen = "name = 'x'", position = NodePosition(1.0, 2.0),
            ),
        )
        assertRoundTrips(
            BuildCollectionSearchDocumentNode.serializer(),
            BuildCollectionSearchDocumentNode(
                id = "n1", name = "N", description = "D", expression = "x", index = "Admin Search Index",
                requirePublic = false, requirePublished = false, requireSearchable = false, excludeDeleted = false,
                indexWhen = "t", position = NodePosition(1.0, 2.0),
            ),
        )
        assertRoundTrips(
            BuildProfileSearchDocumentNode.serializer(),
            BuildProfileSearchDocumentNode(
                id = "n1", name = "N", description = "D", expression = "x", index = "Admin Search Index",
                requirePublic = false, requirePublished = false, requireSearchable = false, excludeDeleted = false,
                indexWhen = "t", position = NodePosition(1.0, 2.0),
            ),
        )

        // Default-valued nodes round-trip too — exercises the serializer's skip-default (encode) and
        // absent-field (decode) arms, the other half of each field's serialization branch.
        assertRoundTrips(BuildMetadataSearchDocumentNode.serializer(), BuildMetadataSearchDocumentNode(id = "n1"))
        assertRoundTrips(BuildCollectionSearchDocumentNode.serializer(), BuildCollectionSearchDocumentNode(id = "n1"))
        assertRoundTrips(BuildProfileSearchDocumentNode.serializer(), BuildProfileSearchDocumentNode(id = "n1"))
    }

    @Test
    fun `build nodes fall back to defaults when settings are absent`() {
        val m = json.decodeFromString(BuildMetadataSearchDocumentNode.serializer(), """{"id":"only"}""")
        assertEquals("", m.expression)
        assertEquals(SearchDocumentPipeline.DEFAULT_INDEX, m.index)
        assertEquals(true, m.requirePublic)
        assertEquals(true, m.requirePublished)
        assertEquals(true, m.requireSearchable)
        assertEquals(true, m.excludeDeleted)
        assertEquals("", m.indexWhen)

        val c = json.decodeFromString(BuildCollectionSearchDocumentNode.serializer(), """{"id":"only"}""")
        assertEquals(SearchDocumentPipeline.DEFAULT_INDEX, c.index)
        assertEquals(true, c.requirePublished)

        val p = json.decodeFromString(BuildProfileSearchDocumentNode.serializer(), """{"id":"only"}""")
        assertEquals(SearchDocumentPipeline.PROFILE_INDEX, p.index)
        assertEquals(true, p.requirePublished)
    }
}
