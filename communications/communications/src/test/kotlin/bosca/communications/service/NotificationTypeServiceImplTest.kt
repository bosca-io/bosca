package bosca.communications.service

import bosca.communications.model.NotificationType
import bosca.communications.repository.NotificationTypeRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies catalog CRUD, key validation, and the system-row
 * guardrails (undeletable, optional flag immutable) in
 * [NotificationTypeServiceImpl].
 */
class NotificationTypeServiceImplTest {

    private val repository = InMemoryTypes(
        NotificationType(key = "transactional", name = "Account activity", optional = false, system = true, displayOrder = 0),
        NotificationType(key = "digest", name = "Digests", optional = true, system = false, displayOrder = 2),
        NotificationType(key = "marketing", name = "Product news & offers", optional = true, system = false, displayOrder = 3),
    )
    private val service = NotificationTypeServiceImpl(repository)

    /**
     * The catalog lists in presentation order.
     */
    @Test
    fun list_returnsCatalogInDisplayOrder() = runBlocking {
        service.set("order-updates", "Order updates", null, true, true, true, 1, false)
        assertEquals(
            listOf("transactional", "order-updates", "digest", "marketing"),
            service.list().map { it.key },
        )
    }

    /**
     * Admins can define a new type with a lowercase slug key, and
     * it reads back by key.
     */
    @Test
    fun set_createsNewType() = runBlocking {
        val created = service.set("order-updates", "Order updates", "Shipping and delivery status.", true, false, true, 10, false)
        assertEquals("order-updates", created.key)
        assertFalse(created.system)
        assertFalse(created.defaultEmailEnabled)
        assertTrue(created.defaultPushEnabled)
        assertEquals(created, service.get("order-updates"))
    }

    @Test
    fun `set persists explicit hidden state`() = runBlocking {
        val hidden = service.set("internal-updates", "Internal updates", null, true, false, false, 10, true)
        assertTrue(hidden.hidden)
        assertFalse(hidden.defaultEmailEnabled)
        assertFalse(hidden.defaultPushEnabled)

        val renamed = service.set("internal-updates", "Internal notices", null, true, true, true, 20, false)
        assertFalse(renamed.hidden)
        assertTrue(renamed.defaultEmailEnabled)
        assertTrue(renamed.defaultPushEnabled)
        assertEquals("Internal notices", renamed.name)
    }

    /**
     * Presentation fields of a system type stay editable — only the
     * optional flag is locked.
     */
    @Test
    fun set_allowsRenamingSystemTypes() = runBlocking {
        val renamed = service.set("transactional", "Account messages", "Updated description.", false, true, true, 5, false)
        assertEquals("Account messages", renamed.name)
        assertEquals(5, renamed.displayOrder)
    }

    /**
     * The optional flag of a system type cannot be changed —
     * flipping security/transactional to optional would break the
     * always-deliver guarantee.
     */
    @Test
    fun set_rejectsChangingOptionalOnSystemTypes() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.set("transactional", "Account activity", null, true, true, true, 0, false) }
        }
    }

    @Test
    fun set_rejectsDisablingEitherDefaultForNonOptionalType() {
        for ((email, push) in listOf(false to true, true to false, false to false)) {
            assertFailsWith<IllegalArgumentException> {
                runBlocking { service.set("transactional", "Account activity", null, false, email, push, 0, false) }
            }
        }
    }

    /**
     * Custom types have no such lock: flipping the optional flag
     * in either direction persists.
     */
    @Test
    fun set_flipsOptionalOnCustomTypes() = runBlocking {
        service.set("order-updates", "Order updates", null, true, true, true, 10, false)
        assertFalse(service.set("order-updates", "Order updates", null, false, true, true, 10, false).optional)
        assertFalse(service.get("order-updates")?.optional ?: true)
        assertTrue(service.set("order-updates", "Order updates", null, true, true, true, 10, false).optional)
    }

    /**
     * Keys are the immutable contract sending code references, so
     * malformed keys are rejected at create time.
     */
    @Test
    fun set_rejectsMalformedKeys() {
        for (key in listOf("Order Updates", "UPPER", "9starts-with-digit", "spaced key", "")) {
            assertFailsWith<IllegalArgumentException>("key: $key") {
                runBlocking { service.set(key, "Name", null, true, true, true, 0, false) }
            }
        }
    }

    /**
     * Blank names are rejected — the name is what users see.
     */
    @Test
    fun set_rejectsBlankName() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.set("order-updates", "  ", null, true, true, true, 0, false) }
        }
    }

    /**
     * Non-system types delete; unknown keys report false.
     */
    @Test
    fun delete_removesCustomTypes() = runBlocking {
        service.set("order-updates", "Order updates", null, true, true, true, 10, false)
        assertTrue(service.delete("order-updates"))
        assertNull(service.get("order-updates"))
        assertFalse(service.delete("never-existed"))
    }

    @Test
    fun `delete reports false when the repository does not remove an existing custom type`() = runBlocking {
        val repository = mockk<NotificationTypeRepository>()
        val existing = NotificationType(key = "order-updates", name = "Order updates")
        coEvery { repository.get(existing.key) } returns existing
        coEvery { repository.delete(existing.key) } returns 0

        assertFalse(NotificationTypeServiceImpl(repository).delete(existing.key))
    }

    /**
     * Optional seeded defaults are admin-managed catalog entries,
     * so they remain deletable even though their keys are well-known.
     */
    @Test
    fun delete_removesOptionalSeededDefaults() = runBlocking {
        assertTrue(service.delete("digest"))
        assertTrue(service.delete("marketing"))
        assertNull(service.get("digest"))
        assertNull(service.get("marketing"))
    }

    /**
     * System types cannot be deleted.
     */
    @Test
    fun delete_rejectsSystemTypes() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.delete("transactional") }
        }
    }

    private class InMemoryTypes(vararg seed: NotificationType) : NotificationTypeRepository {
        private val rows = seed.associateBy { it.key }.toMutableMap()

        override suspend fun list(): List<NotificationType> =
            rows.values.sortedWith(compareBy({ it.displayOrder }, { it.key }))

        override suspend fun get(key: String): NotificationType? = rows[key]

        override suspend fun upsert(
            key: String,
            name: String,
            description: String?,
            optional: Boolean,
            defaultEmailEnabled: Boolean,
            defaultPushEnabled: Boolean,
            displayOrder: Int,
            hidden: Boolean,
        ): NotificationType {
            val row = NotificationType(
                key = key,
                name = name,
                description = description,
                optional = optional,
                defaultEmailEnabled = defaultEmailEnabled,
                defaultPushEnabled = defaultPushEnabled,
                system = rows[key]?.system ?: false,
                displayOrder = displayOrder,
                hidden = hidden,
            )
            rows[key] = row
            return row
        }

        override suspend fun delete(key: String): Int =
            if (rows[key]?.system == false) { rows.remove(key); 1 } else 0
    }
}
