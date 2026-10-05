package bosca.nats.admin.graphql

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class NatsAdminTest {

    @Test
    fun `NatsAdmin is a singleton object`() {
        val instance = NatsAdmin
        assertNotNull(instance)
    }

    @Test
    fun `NatsAdmin identity is stable across references`() {
        val a = NatsAdmin
        val b = NatsAdmin
        assertSame(a, b)
    }

    @Test
    fun `NatsAdminMutation is a singleton object`() {
        val instance = NatsAdminMutation
        assertNotNull(instance)
    }

    @Test
    fun `NatsAdminMutation identity is stable across references`() {
        val a = NatsAdminMutation
        val b = NatsAdminMutation
        assertSame(a, b)
    }
}
