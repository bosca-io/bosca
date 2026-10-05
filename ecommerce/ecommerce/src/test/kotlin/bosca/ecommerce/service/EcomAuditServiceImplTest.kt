package bosca.ecommerce.service

import bosca.ecommerce.model.Audit
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.LengthUnit
import bosca.ecommerce.model.WeightUnit
import bosca.ecommerce.repository.AuditRepository
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * the typed [EcomAuditService.record] overload must capture an entity's full state
 * in its snapshot — including fields whose value equals the model default. The primary `Json` omits
 * default-valued properties (`encodeDefaults = false`), so the service derives its own `Json` with
 * `encodeDefaults = true`; these tests pin that the derived encoder, not the primary, is used.
 */
@OptIn(ExperimentalUuidApi::class)
class EcomAuditServiceImplTest {

    private val auditRepository = mockk<AuditRepository>()

    // A primary Json mirroring the DI one: contextual serializers, encodeDefaults left at its default
    // (false). If the service (incorrectly) encoded with this, default-valued fields would be dropped.
    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUID::class, UUIDSerializer())
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeSerializer())
        }
    }
    private val service = EcomAuditServiceImpl(auditRepository, json)

    @Test
    fun `typed record captures default-valued fields the primary Json would omit`() = runTest {
        val captured = slot<Audit>()
        coEvery { auditRepository.add(capture(captured)) } answers { captured.captured }

        // before is at the model defaults (INCHES/POUNDS); after is changed. With the primary Json the
        // before snapshot would drop both unit fields entirely — the bug this overload fixes.
        val before = Company(organizationId = UUID.random(), profileId = UUID.random())
        val after = before.copy(lengthUnit = LengthUnit.CENTIMETERS, weightUnit = WeightUnit.KILOGRAMS)

        service.record("company", before.id, "units_updated", Company.serializer(), before = before, after = after)

        val beforeJson = requireNotNull(captured.captured.before).jsonObject
        val afterJson = requireNotNull(captured.captured.after).jsonObject
        // The default-valued before snapshot still carries both unit fields.
        assertEquals("INCHES", beforeJson.getValue("lengthUnit").jsonPrimitive.content)
        assertEquals("POUNDS", beforeJson.getValue("weightUnit").jsonPrimitive.content)
        assertEquals("CENTIMETERS", afterJson.getValue("lengthUnit").jsonPrimitive.content)
        assertEquals("KILOGRAMS", afterJson.getValue("weightUnit").jsonPrimitive.content)
    }

    @Test
    fun `typed record leaves null before-after as null (create and delete shapes)`() = runTest {
        val captured = slot<Audit>()
        coEvery { auditRepository.add(capture(captured)) } answers { captured.captured }
        val company = Company(organizationId = UUID.random(), profileId = UUID.random())

        // create shape: only after
        service.record("company", company.id, "created", Company.serializer(), after = company)
        assertEquals(null, captured.captured.before)
        requireNotNull(captured.captured.after)

        // delete shape: only before
        service.record("company", company.id, "deleted", Company.serializer(), before = company)
        assertEquals(null, captured.captured.after)
        requireNotNull(captured.captured.before)
    }

    @Test
    fun `JsonElement record passes all attribution and snapshot fields straight through`() = runTest {
        val captured = slot<Audit>()
        coEvery { auditRepository.add(capture(captured)) } answers { captured.captured }

        val entityId = UUID.random()
        val principalId = UUID.random()
        val profileId = UUID.random()
        val storeId = UUID.random()
        val before = buildJsonObject { put("state", "old") }
        val after = buildJsonObject { put("state", "new") }
        val details = buildJsonObject { put("note", "ad-hoc") }

        service.record(
            entityType = "payment",
            entityId = entityId,
            action = "captured",
            before = before,
            after = after,
            principalId = principalId,
            profileId = profileId,
            storeId = storeId,
            details = details,
        )

        val row = captured.captured
        assertEquals("payment", row.entityType)
        assertEquals(entityId, row.entityId)
        assertEquals("captured", row.action)
        assertEquals(principalId, row.principalId)
        assertEquals(profileId, row.profileId)
        assertEquals(storeId, row.storeId)
        // The raw JsonElements are stored verbatim (no re-encoding).
        assertSame(before, row.before)
        assertSame(after, row.after)
        assertSame(details, row.details)
    }

    @Test
    fun `JsonElement record defaults attribution and snapshots to null for an action-only entry`() = runTest {
        val captured = slot<Audit>()
        coEvery { auditRepository.add(capture(captured)) } answers { captured.captured }

        service.record(entityType = "store", entityId = UUID.random(), action = "reindexed")

        val row = captured.captured
        assertEquals(null, row.before)
        assertEquals(null, row.after)
        assertEquals(null, row.details)
        assertEquals(null, row.principalId)
        assertEquals(null, row.profileId)
        assertEquals(null, row.storeId)
    }

    @Test
    fun `typed record encodes a non-null details element verbatim`() = runTest {
        val captured = slot<Audit>()
        coEvery { auditRepository.add(capture(captured)) } answers { captured.captured }

        val details = buildJsonObject { put("version", 2) }
        service.record(
            "product", UUID.random(), "content_published", Company.serializer(),
            before = null, after = null, details = details,
        )

        // The typed overload passes details through untouched (it only encodes before/after).
        assertSame(details, captured.captured.details)
        assertEquals(null, captured.captured.before)
        assertEquals(null, captured.captured.after)
    }

    @Test
    fun `list forwards every filter and paging arg and returns the repository rows`() = runTest {
        val entityId = UUID.random()
        val storeId = UUID.random()
        val rows = listOf(
            Audit(entityType = "company", entityId = entityId, action = "created"),
        )
        coEvery {
            auditRepository.list("company", entityId, storeId, "created", 5, 25)
        } returns rows

        val result = service.list(
            entityType = "company", entityId = entityId, storeId = storeId,
            action = "created", offset = 5, limit = 25,
        )

        assertEquals(rows, result)
        coVerify(exactly = 1) { auditRepository.list("company", entityId, storeId, "created", 5, 25) }
    }

    @Test
    fun `list passes null filters through with the default paging`() = runTest {
        coEvery { auditRepository.list(null, null, null, null, 0, 50) } returns emptyList()

        assertEquals(emptyList(), service.list())
        coVerify(exactly = 1) { auditRepository.list(null, null, null, null, 0, 50) }
    }
}
