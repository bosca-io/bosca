@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.service

import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.model.ProfileVisibility
import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.recommendations.model.PersonalizationSignalValueType
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Tests for [ProfileSignalComputeServiceImpl] — the JSONata compute (keyed values, context gating,
 * null/erroring-expression handling) and the write-time / backfill persistence paths.
 */
class ProfileSignalComputeServiceImplTest {

    private val definitions = mockk<PersonalizationSignalService>(relaxed = true)
    private val attributes = mockk<ProfileAttributeService>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val service = ProfileSignalComputeServiceImpl(definitions, attributes, json)

    private fun attribute(
        typeId: String = "bosca.profiles.age",
        value: JsonElement = buildJsonObject { put("band", "25-34") },
        confidence: Int = 100,
    ) = ProfileAttribute(
        id = UUID.random(), profile = UUID.random(), typeId = typeId,
        visibility = ProfileVisibility.PUBLIC, confidence = confidence, priority = 0, source = "user",
        attributes = value, verified = true,
    )

    private fun def(
        key: String = "age_band",
        expression: String = "attributes.band",
        valueType: PersonalizationSignalValueType = PersonalizationSignalValueType.CATEGORICAL,
    ) = PersonalizationSignalDefinition(
        id = UUID.random(), key = key, sourceType = PersonalizationSignalSourceType.ATTRIBUTE,
        sourceId = "bosca.profiles.age", expression = expression, valueType = valueType,
    )

    @Test
    fun `compute produces keyed values from the JSONata`() {
        val result = service.compute(attribute(), listOf(def()))
        assertEquals(1, result.size)
        assertEquals("age_band", result[0].key)
        assertEquals("25-34", result[0].value.jsonPrimitive.content)
    }

    @Test
    fun `compute can gate on attribute context (confidence)`() {
        val gated = def(expression = "confidence >= 80 ? attributes.band : undefined")
        assertEquals(1, service.compute(attribute(confidence = 100), listOf(gated)).size)
        assertTrue(service.compute(attribute(confidence = 50), listOf(gated)).isEmpty())
    }

    @Test
    fun `compute excludes a signal whose JSONata returns nothing`() {
        assertTrue(service.compute(attribute(), listOf(def(expression = "attributes.missing"))).isEmpty())
    }

    @Test
    fun `compute skips a signal whose JSONata errors rather than failing`() {
        assertTrue(service.compute(attribute(), listOf(def(expression = "\$notAFunction()"))).isEmpty())
    }

    @Test
    fun `compute returns empty when there are no definitions`() {
        assertTrue(service.compute(attribute(), emptyList()).isEmpty())
    }

    @Test
    fun `compute handles an attribute with a null value`() {
        assertTrue(service.compute(attribute(value = JsonNull), listOf(def(expression = "attributes"))).isEmpty())
    }

    @Test
    fun `computeForAttributes persists computed signals per attribute`() = runTest {
        val attr = attribute()
        coEvery { attributes.getById(attr.id) } returns attr
        coEvery {
            definitions.getEnabledBySource(PersonalizationSignalSourceType.ATTRIBUTE, "bosca.profiles.age")
        } returns listOf(def())
        service.computeForAttributes(listOf(attr.id, attr.id))
        coVerify(exactly = 1) { attributes.setSignals(attr.id, any()) }
        coVerify(exactly = 0) { attributes.getAttributesByProfile(any()) }
    }

    @Test
    fun `computeForAttributes writes null when nothing matches`() = runTest {
        val attr = attribute()
        coEvery { attributes.getById(attr.id) } returns attr
        coEvery { definitions.getEnabledBySource(any(), any()) } returns emptyList()
        service.computeForAttributes(listOf(attr.id))
        coVerify { attributes.setSignals(attr.id, null) }
    }

    @Test
    fun `computeForAttributes skips a missing attribute`() = runTest {
        val id = UUID.random()
        coEvery { attributes.getById(id) } returns null
        service.computeForAttributes(listOf(id))
        coVerify(exactly = 0) { attributes.setSignals(any(), any()) }
    }

    @Test
    fun `computeForProfileType recomputes only the profile's attributes of the named type`() = runTest {
        val profileId = UUID.random()
        val email = attribute(typeId = "bosca.profiles.email")
        val age = attribute(typeId = "bosca.profiles.age")
        coEvery { attributes.getAttributesByProfile(profileId) } returns listOf(email, age)
        coEvery { definitions.getEnabledBySource(any(), any()) } returns listOf(def())
        service.computeForProfileType(profileId, "bosca.profiles.email")
        coVerify(exactly = 1) { attributes.setSignals(email.id, any()) }
        coVerify(exactly = 0) { attributes.setSignals(age.id, any()) }
    }

    @Test
    fun `recomputeForSource recomputes every attribute of an attribute type`() = runTest {
        val attr = attribute()
        coEvery { attributes.getByTypeId("bosca.profiles.age") } returns listOf(attr)
        coEvery { definitions.getEnabledBySource(any(), any()) } returns listOf(def())
        service.recomputeForSource(PersonalizationSignalSourceType.ATTRIBUTE, "bosca.profiles.age")
        coVerify { attributes.setSignals(attr.id, any()) }
    }

    @Test
    fun `recomputeForSource ignores segment sources`() = runTest {
        service.recomputeForSource(PersonalizationSignalSourceType.SEGMENT, "seg-1")
        coVerify(exactly = 0) { attributes.getByTypeId(any()) }
    }
}
