package bosca.storage.graphql

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class StorageSystemsMutationControllerTest {

    @Test
    fun `StorageSystemsMutation is a singleton object`() {
        val instance = StorageSystemsMutation
        assertNotNull(instance)
    }

    @Test
    fun `StorageSystemsMutation identity is stable`() {
        assertSame(StorageSystemsMutation, StorageSystemsMutation)
    }
}
