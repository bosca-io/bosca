package bosca.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class IndexStorageSystemTest {

    @Test
    fun defaultValuesAreNull() {
        val instance = IndexStorageSystem()
        assertNull(instance.id)
        assertNull(instance.name)
    }

    @Test
    fun `equality copy hashes and constructor defaults cover both fields`() {
        val base = IndexStorageSystem(Uuid.random(), "primary")
        assertEquals(base, base)
        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        assertFalse(base.equals(null))
        assertFalse(base.equals("index"))
        assertNotEquals(base, base.copy(id = Uuid.random()))
        assertNotEquals(base, base.copy(name = "secondary"))
        IndexStorageSystem(id = base.id).hashCode()
        IndexStorageSystem(name = base.name).hashCode()
    }
}
