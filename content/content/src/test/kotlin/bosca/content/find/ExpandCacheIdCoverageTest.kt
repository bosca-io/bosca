package bosca.content.find

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * Coverage for [ExpandCacheKeySerializer.fromRemoteKey], the arm not exercised by
 * [ExpandCacheIdTest] (which covers [ExpandCacheId], [ExpandCacheKey.toRemoteKey], and
 * [ExpandCacheKeySerializer.toLocalKey]).
 *
 * [ExpandCacheKeySerializer.fromRemoteKey] splits a remote key back into parts and rebuilds an
 * [ExpandCacheId]. It reads the ordering slot (index 3) through `asJsonElement()`/`asValue()`,
 * both of which resolve `JsonConverter.json` via `provide<Json>()`, so a [Json] provider is
 * registered in the [ProviderRegistry] here — mirroring the pattern in
 * `MetadataAIMutationControllerCoverageTest`.
 *
 * Note on the ordering slot: `toRemoteKey` writes the ordering as its JSON string, but
 * `fromRemoteKey` reads it back with `String.asJsonElement()` (which wraps the raw string into a
 * JSON *string primitive*) and then `asValue<List<Ordering>>()`. Decoding a `List<Ordering>` from
 * a string primitive always fails, so any key whose index-3 slot is present (even an empty string
 * left behind by a null ordering) throws before the offset/limit slots are ever parsed. The
 * offset/limit non-null branches are therefore structurally unreachable via this serializer; the
 * null sides are covered here through a short key that has no index-3 slot.
 */
@OptIn(InternalDI::class)
class ExpandCacheIdCoverageTest {

    private val json = Json

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `fromRemoteKey rebuilds key with only cacheName and id when ordering slot absent`() {
        val id = UUID.random()
        // Raw key with three parts: prefix tag, cache name, id. separateForCacheKey() drops the
        // leading "expd" tag, leaving keyParts = [cacheName, id]; index 3 (ordering) is null.
        val remoteKey = "expd::mycache::$id"

        val result = ExpandCacheKeySerializer.fromRemoteKey(remoteKey)

        assertEquals("mycache", result.cacheName)
        assertEquals(id, result.key.id)
        assertNull(result.key.state)
        assertNull(result.key.ordering)
        assertNull(result.key.offset)
        assertNull(result.key.limit)
    }

    @Test
    fun `fromRemoteKey preserves state when present and ordering slot absent`() {
        val id = UUID.random()
        // keyParts = [cacheName, id, state]; index 3 (ordering) is null.
        val remoteKey = "expd::c1::$id::published"

        val result = ExpandCacheKeySerializer.fromRemoteKey(remoteKey)

        assertEquals("c1", result.cacheName)
        assertEquals(id, result.key.id)
        assertEquals("published", result.key.state)
        assertNull(result.key.ordering)
        assertNull(result.key.offset)
        assertNull(result.key.limit)
    }

    @Test
    fun `fromRemoteKey fails when ordering slot is present`() {
        val id = UUID.random()
        // keyParts index 3 is present ("[]"). String.asJsonElement() wraps it as a JSON string
        // primitive, and asValue<List<Ordering>>() cannot decode a list from a string primitive,
        // so the ordering line throws.
        val remoteKey = "expd::c1::$id::published::[]"

        assertFails {
            ExpandCacheKeySerializer.fromRemoteKey(remoteKey)
        }
    }

    @Test
    fun `fromRemoteKey fails when ordering slot is an empty string`() {
        val id = UUID.random()
        // A null ordering written by toRemoteKey leaves an empty string at index 3. getOrNull(3)
        // returns "" (non-null), so the ordering decode is still attempted and still fails.
        val remoteKey = "expd::c1::$id::published::"

        assertFails {
            ExpandCacheKeySerializer.fromRemoteKey(remoteKey)
        }
    }

    @Test
    fun `fromRemoteKey fails when id part is not a valid UUID`() {
        // keyParts[1] is parsed with UUID.parse; a non-UUID value makes it throw.
        val remoteKey = "expd::c1::not-a-uuid"

        assertFails {
            ExpandCacheKeySerializer.fromRemoteKey(remoteKey)
        }
    }
}
