@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.recommendations.pipeline

import bosca.category.model.Category
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.model.ProfileVisibility
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

/**
 * on a high `ProfileRatingAdded`, the node records a learned (confidence < 100) interest
 * in the rated content's category via [ProfileAttributeService]; low/collection/uncategorized ratings infer
 * nothing, and a repeat rating of a category reinforces the existing interest.
 */
class InferInterestNodeTest {

    private val metadata = mockk<MetadataService>(relaxed = true)
    private val attributes = mockk<ProfileAttributeService>(relaxed = true)
    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<MetadataService> { metadata }
        provides<ProfileAttributeService> { attributes }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("" to value))

    private fun ratingEvent(rating: Int, metadataId: UUID? = UUID.random(), profileId: UUID = UUID.random()) =
        PipelineValue.ofJson(buildJsonObject {
            put("profileId", profileId.toString())
            put("rating", rating)
            if (metadataId != null) put("metadataId", metadataId.toString())
        })

    private fun learnedAttribute(profile: UUID, categoryId: UUID, confidence: Int) = ProfileAttribute(
        id = UUID.random(), profile = profile, typeId = InferInterestNode.LEARNED_INTEREST_TYPE,
        visibility = ProfileVisibility.USER, confidence = confidence, priority = 0, source = "learned",
        attributes = buildJsonObject { put("interest", "Science"); put("category_id", categoryId.toString()) },
    )

    private fun attr(profile: UUID, typeId: String, value: JsonElement?) = ProfileAttribute(
        id = UUID.random(), profile = profile, typeId = typeId, visibility = ProfileVisibility.USER,
        confidence = 50, priority = 0, source = "x", attributes = value,
    )

    @Test
    fun `a five-star rating records a new learned interest in the content's category`() = runTest {
        val profile = UUID.random()
        val metadataId = UUID.random()
        val category = Category(id = UUID.random(), name = "Science")
        coEvery { metadata.getCategories(metadataId) } returns listOf(category)
        coEvery { attributes.getAttributesByProfile(profile) } returns emptyList()
        val captured = slot<List<ProfileAttributeInput>>()
        coEvery { attributes.addAttributes(profile, capture(captured)) } returns emptyList()

        InferInterestNode(id = "n1").run(context, inputs(ratingEvent(5, metadataId, profile)))

        val input = captured.captured.single()
        assertEquals(UUID.NIL, input.id) // new insert
        assertEquals(InferInterestNode.LEARNED_INTEREST_TYPE, input.typeId)
        assertEquals(70, input.confidence) // strong rating, still < 100
        assertEquals("learned", input.source)
        val value = input.attributes as JsonObject
        assertEquals("Science", value["interest"]?.jsonPrimitive?.content)
        assertEquals(category.id.toString(), value["category_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a four-star rating records a lower base confidence`() = runTest {
        val profile = UUID.random()
        val metadataId = UUID.random()
        coEvery { metadata.getCategories(metadataId) } returns listOf(Category(id = UUID.random(), name = "Tech"))
        coEvery { attributes.getAttributesByProfile(profile) } returns emptyList()
        val captured = slot<List<ProfileAttributeInput>>()
        coEvery { attributes.addAttributes(profile, capture(captured)) } returns emptyList()

        InferInterestNode(id = "n1").run(context, inputs(ratingEvent(4, metadataId, profile)))

        assertEquals(55, captured.captured.single().confidence)
    }

    @Test
    fun `a repeat rating of the same category reinforces the existing interest`() = runTest {
        val profile = UUID.random()
        val metadataId = UUID.random()
        val category = Category(id = UUID.random(), name = "Science")
        val existing = learnedAttribute(profile, category.id, confidence = 60)
        coEvery { metadata.getCategories(metadataId) } returns listOf(category)
        coEvery { attributes.getAttributesByProfile(profile) } returns listOf(existing)
        val captured = slot<List<ProfileAttributeInput>>()
        coEvery { attributes.addAttributes(profile, capture(captured)) } returns emptyList()

        InferInterestNode(id = "n1").run(context, inputs(ratingEvent(4, metadataId, profile)))

        val input = captured.captured.single()
        assertEquals(existing.id, input.id) // reinforces in place, not a new insert
        assertEquals(65, input.confidence) // 60 + reinforcement, still < 100
    }

    @Test
    fun `reinforcement is capped below certainty`() = runTest {
        val profile = UUID.random()
        val metadataId = UUID.random()
        val category = Category(id = UUID.random(), name = "Science")
        coEvery { metadata.getCategories(metadataId) } returns listOf(category)
        coEvery { attributes.getAttributesByProfile(profile) } returns listOf(learnedAttribute(profile, category.id, confidence = 88))
        val captured = slot<List<ProfileAttributeInput>>()
        coEvery { attributes.addAttributes(profile, capture(captured)) } returns emptyList()

        InferInterestNode(id = "n1").run(context, inputs(ratingEvent(5, metadataId, profile)))

        assertEquals(90, captured.captured.single().confidence) // capped at MAX_LEARNED_CONFIDENCE
    }

    @Test
    fun `a non-matching existing attribute does not reinforce and a new interest is recorded`() = runTest {
        val profile = UUID.random()
        val metadataId = UUID.random()
        val category = Category(id = UUID.random(), name = "Science")
        coEvery { metadata.getCategories(metadataId) } returns listOf(category)
        // None of these match this category's learned interest: a different type, a non-object value, a value
        // with no category_id, and a learned interest in a *different* category.
        coEvery { attributes.getAttributesByProfile(profile) } returns listOf(
            attr(profile, "bosca.profiles.age", buildJsonObject { put("band", "25-34") }),
            attr(profile, InferInterestNode.LEARNED_INTEREST_TYPE, JsonNull),
            attr(profile, InferInterestNode.LEARNED_INTEREST_TYPE, buildJsonObject { }),
            attr(
                profile,
                InferInterestNode.LEARNED_INTEREST_TYPE,
                buildJsonObject { put("category_id", buildJsonObject {}) },
            ),
            learnedAttribute(profile, UUID.random(), confidence = 60),
        )
        val captured = slot<List<ProfileAttributeInput>>()
        coEvery { attributes.addAttributes(profile, capture(captured)) } returns emptyList()

        InferInterestNode(id = "n1").run(context, inputs(ratingEvent(4, metadataId, profile)))

        val input = captured.captured.single()
        assertEquals(UUID.NIL, input.id) // no match → a new insert, not a reinforcement
        assertEquals(55, input.confidence)
    }

    @Test
    fun `a low rating infers nothing`() = runTest {
        InferInterestNode(id = "n1").run(context, inputs(ratingEvent(3)))
        coVerify(exactly = 0) { attributes.addAttributes(any(), any()) }
        coVerify(exactly = 0) { metadata.getCategories(any()) }
    }

    @Test
    fun `a collection rating with no metadata infers nothing`() = runTest {
        InferInterestNode(id = "n1").run(context, inputs(ratingEvent(5, metadataId = null)))
        coVerify(exactly = 0) { attributes.addAttributes(any(), any()) }
    }

    @Test
    fun `an event with a malformed metadata id infers nothing`() = runTest {
        val ev = PipelineValue.ofJson(buildJsonObject {
            put("profileId", UUID.random().toString()); put("rating", 5); put("metadataId", "not-a-uuid")
        })
        InferInterestNode(id = "n1").run(context, inputs(ev))
        coVerify(exactly = 0) { attributes.addAttributes(any(), any()) }
    }

    @Test
    fun `dry run without a trace passes the input through`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = null)
        val out = InferInterestNode(id = "n1").run(dry, inputs(ratingEvent(5)))
        assertEquals(NodeResult.Output::class, out::class)
    }

    @Test
    fun `an event with a non-integer rating infers nothing`() = runTest {
        val ev = PipelineValue.ofJson(buildJsonObject {
            put("profileId", UUID.random().toString()); put("rating", "five"); put("metadataId", UUID.random().toString())
        })
        InferInterestNode(id = "n1").run(context, inputs(ev))
        coVerify(exactly = 0) { attributes.addAttributes(any(), any()) }
    }

    @Test
    fun `events with non-primitive rating or ids infer nothing`() = runTest {
        val validProfile = UUID.random().toString()
        val validMetadata = UUID.random().toString()
        val events = listOf(
            buildJsonObject {
                put("profileId", validProfile)
                put("rating", buildJsonObject {})
                put("metadataId", validMetadata)
            },
            buildJsonObject {
                put("profileId", buildJsonObject {})
                put("rating", 5)
                put("metadataId", validMetadata)
            },
            buildJsonObject {
                put("profileId", JsonNull)
                put("rating", 5)
                put("metadataId", validMetadata)
            },
            buildJsonObject {
                put("profileId", validProfile)
                put("rating", 5)
                put("metadataId", buildJsonObject {})
            },
        )

        events.forEach { event ->
            InferInterestNode(id = "n1").run(context, inputs(PipelineValue.ofJson(event)))
        }

        coVerify(exactly = 0) { attributes.addAttributes(any(), any()) }
    }

    @Test
    fun `an event with no profile id infers nothing`() = runTest {
        val ev = PipelineValue.ofJson(buildJsonObject { put("rating", 5); put("metadataId", UUID.random().toString()) })
        InferInterestNode(id = "n1").run(context, inputs(ev))
        coVerify(exactly = 0) { attributes.addAttributes(any(), any()) }
    }

    @Test
    fun `content with no categories infers nothing`() = runTest {
        val metadataId = UUID.random()
        coEvery { metadata.getCategories(metadataId) } returns emptyList()
        InferInterestNode(id = "n1").run(context, inputs(ratingEvent(5, metadataId)))
        coVerify(exactly = 0) { attributes.addAttributes(any(), any()) }
    }

    @Test
    fun `does nothing when there is no input`() = runTest {
        val out = InferInterestNode(id = "n1").run(context, NodeInputs(emptyMap()))
        coVerify(exactly = 0) { attributes.addAttributes(any(), any()) }
        assertEquals(NodeResult.Output::class, out::class)
    }

    @Test
    fun `dry run records the action and mutates nothing`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)
        InferInterestNode(id = "n1").run(dry, inputs(ratingEvent(5)))
        assertEquals(true, trace.actions.containsKey("n1"))
        coVerify(exactly = 0) { attributes.addAttributes(any(), any()) }
    }

    @Test
    fun `construction defaults and serialization round-trip`() {
        assertEquals("", InferInterestNode(id = "id-1").name)
        val full = InferInterestNode(id = "id-2", name = "Infer", description = "d", position = NodePosition())
        val encoded = json.encodeToString(InferInterestNode.serializer(), full)
        assertEquals("id-2", json.decodeFromString(InferInterestNode.serializer(), encoded).id)
        assertEquals("id-3", json.decodeFromString(InferInterestNode.serializer(), """{"id":"id-3"}""").id)
        // Defaults-only encode → the serializer's "value equals default → skip" branches.
        val skip = json.encodeToString(InferInterestNode.serializer(), InferInterestNode(id = "id-4"))
        assertEquals("id-4", json.decodeFromString(InferInterestNode.serializer(), skip).id)
        val withDefaults = Json {
            serializersModule = SerializersModule { contextual(UUIDSerializer()) }; encodeDefaults = true
        }
        val re = withDefaults.encodeToString(InferInterestNode.serializer(), InferInterestNode("id-5"))
        assertEquals("id-5", withDefaults.decodeFromString(InferInterestNode.serializer(), re).id)
        // The strict decoder rejects an unknown key → the unknown-field branch.
        assertFailsWith<Exception> {
            json.decodeFromString(InferInterestNode.serializer(), """{"id":"x","unexpected":1}""")
        }
    }
}
