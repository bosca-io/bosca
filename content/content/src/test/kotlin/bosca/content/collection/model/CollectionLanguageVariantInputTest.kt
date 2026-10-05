package bosca.content.collection.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionLanguageVariantInputTest {

    @Test
    fun `toVariant defaults to pending workflow state`() {
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "es",
            name = "Spanish"
        )
        val variant = input.toVariant()
        assertEquals("pending", variant.workflowStateId)
    }

    @Test
    fun `toVariant accepts custom workflow state`() {
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "fr",
            name = "French"
        )
        val variant = input.toVariant(workflowStateId = "published")
        assertEquals("published", variant.workflowStateId)
    }

    @Test
    fun `toVariant preserves all fields`() {
        val id = UUID.random()
        val input = CollectionLanguageVariantInput(
            id = id,
            languageTag = "de",
            name = "German",
            description = "German variant"
        )
        val variant = input.toVariant(workflowStateId = "review")
        assertEquals(id, variant.id)
        assertEquals("de", variant.languageTag)
        assertEquals("German", variant.name)
        assertEquals("German variant", variant.description)
        assertEquals("review", variant.workflowStateId)
    }

    @Test
    fun `toVariant with null description`() {
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "es",
            name = "Spanish",
            description = null
        )
        val variant = input.toVariant()
        assertNull(variant.description)
    }

    @Test
    fun `toVariant with null attributes`() {
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "es",
            name = "Spanish",
            attributes = null
        )
        val variant = input.toVariant()
        assertNull(variant.attributes)
    }

    @Test
    fun `toVariant with attributes`() {
        val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "es",
            name = "Spanish",
            attributes = attrs
        )
        val variant = input.toVariant()
        assertEquals(attrs, variant.attributes)
    }

    @Test
    fun `toVariant with advertised state produces advertised variant`() {
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "es",
            name = "Spanish"
        )
        val variant = input.toVariant(workflowStateId = "advertised")
        assertEquals("advertised", variant.workflowStateId)
        assertTrue(variant.isAdvertised)
        assertFalse(variant.isPublished)
    }

    @Test
    fun `toVariant with published state produces published variant`() {
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "es",
            name = "Spanish"
        )
        val variant = input.toVariant(workflowStateId = "published")
        assertEquals("published", variant.workflowStateId)
        assertTrue(variant.isPublished)
        assertFalse(variant.isAdvertised)
    }

    @Test
    fun `toVariant does not set pending state fields`() {
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "es",
            name = "Spanish"
        )
        val variant = input.toVariant(workflowStateId = "published")
        assertNull(variant.workflowStatePendingId)
        assertNull(variant.workflowStateValid)
        assertNull(variant.deleteWorkflowId)
    }

    @Test
    fun `toVariant defaults visibility fields`() {
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "es",
            name = "Spanish"
        )
        val variant = input.toVariant()
        assertFalse(variant.public)
        assertFalse(variant.publicList)
        assertFalse(variant.publicSupplementary)
        assertTrue(variant.searchable)
        assertTrue(variant.recommendable)
    }

    @Test
    fun `toVariant carries custom visibility fields`() {
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "es",
            name = "Spanish",
            public = true,
            publicList = true,
            publicSupplementary = true,
            searchable = false,
            recommendable = false,
        )
        val variant = input.toVariant()
        assertTrue(variant.public)
        assertTrue(variant.publicList)
        assertTrue(variant.publicSupplementary)
        assertFalse(variant.searchable)
        assertFalse(variant.recommendable)
    }

    @Test
    fun `input leaves independently managed eligibility fields unspecified`() {
        val input = CollectionLanguageVariantInput(
            id = UUID.random(),
            languageTag = "es",
            name = "Spanish"
        )
        assertFalse(input.public)
        assertFalse(input.publicList)
        assertFalse(input.publicSupplementary)
        assertNull(input.searchable)
        assertNull(input.recommendable)
    }

    @Test
    fun `editing preserves independently managed eligibility flags`() {
        val current = CollectionLanguageVariant(
            id = UUID.random(),
            languageTag = "es",
            name = "Current",
            searchable = false,
            recommendable = false,
        )

        val updated = CollectionLanguageVariantInput(
            id = current.id,
            languageTag = current.languageTag,
            name = "Updated",
        ).toVariant(current)

        assertFalse(updated.searchable)
        assertFalse(updated.recommendable)

        val enabled = CollectionLanguageVariantInput(
            id = current.id,
            languageTag = current.languageTag,
            name = "Updated",
            searchable = true,
            recommendable = true,
        ).toVariant(current)
        assertTrue(enabled.searchable)
        assertTrue(enabled.recommendable)
    }
}
