package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CatalogPickerTest {
    @Test
    fun `invalid choices retain the saved value and state stays local to each instance`() = runBlocking {
        val first = CatalogPicker("first")
        val second = CatalogPicker("second")
        first.save("button")
        assertEquals("Saved Button states.", first.message)
        first.save("unavailable")
        assertEquals("button", first.value)
        assertEquals("Choose an available example.", first.error)
        first.save("unknown")
        assertEquals(1, first.saves)
        first.toggleFailure()
        first.toggleDisabled()
        first.toggleLoading()
        first.save("panel")
        assertEquals("", first.error)
        assertEquals("Saved Panels.", first.message)
        assertEquals(2, first.saves)
        assertEquals("", second.value)
        assertEquals(0, second.saves)
        assertFalse(second.failSearch || second.disabled || second.loading)
    }
}
