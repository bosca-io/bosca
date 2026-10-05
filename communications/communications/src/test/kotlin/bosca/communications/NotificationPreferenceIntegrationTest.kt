@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.communications

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.communications.configuration.CommunicationsMigration
import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatusType
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.repository.DeliveryEventRepositoryImpl
import bosca.communications.repository.DeliveryStatusRepositoryImpl
import bosca.communications.repository.NotificationPreferenceRepositoryImpl
import bosca.communications.repository.NotificationPreferenceMappingRepositoryImpl
import bosca.communications.repository.NotificationSettingsRepositoryImpl
import bosca.communications.repository.NotificationTypeRepositoryImpl
import bosca.communications.repository.SuppressionListRepositoryImpl
import bosca.communications.repository.UnsubscribeTokenRepositoryImpl
import bosca.communications.service.DeliveryTrackingServiceImpl
import bosca.communications.service.GateDecision
import bosca.communications.service.GateReasons
import bosca.communications.service.NotificationPreferenceGateImpl
import bosca.communications.service.NotificationPreferenceServiceImpl
import bosca.communications.service.NotificationPreferenceMappingServiceImpl
import bosca.communications.service.NotificationTypeServiceImpl
import bosca.serialization.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end test of the notification preference stack against a
 * real Postgres: the staged V1→seed→current migration (proving the
 * legacy email_preferences carry-forward is lossless), the type
 * catalog, the preference service, and the delivery gate — all on
 * real Flyway migrations and KSP-generated repositories.
 *
 * The legacy rows are seeded between V1 and the remaining migrations (exactly how a
 * production database would look), so the migration-fidelity test
 * exercises the real upgrade path. Legacy fixtures use dedicated
 * profile ids and a `legacy-` token prefix that per-test cleanup
 * never touches.
 */
class NotificationPreferenceIntegrationTest {

    private class CommunicationsV1Migration : Migration {
        override val schema: String = "communications"
        override val resources: List<String> = listOf("V1__communications.sql")
    }

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_communications_pref_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "communications-pref-test",
            )
        )
        private var schemaInitialized = false

        private val LEGACY_A: UUID = UUID.parse("aaaaaaaa-0000-0000-0000-00000000000a")
        private val LEGACY_B: UUID = UUID.parse("aaaaaaaa-0000-0000-0000-00000000000b")
        private val LEGACY_UPDATED_AT: OffsetDateTime = OffsetDateTime.parse("2026-01-02T03:04:05Z")
        private val LEGACY_DELIVERY_EVENT: UUID = UUID.parse("bbbbbbbb-0000-0000-0000-000000000001")
        private val LEGACY_DELIVERY_MESSAGE: UUID = UUID.parse("bbbbbbbb-0000-0000-0000-000000000002")
        private val LEGACY_DELIVERY_RECIPIENT: UUID = UUID.parse("bbbbbbbb-0000-0000-0000-000000000003")
        private val LEGACY_DELIVERY_CREATED: OffsetDateTime = OffsetDateTime.parse("2026-01-03T04:05:06Z")
        private val LEGACY_DELIVERY_UPDATED: OffsetDateTime = OffsetDateTime.parse("2026-01-03T04:06:07Z")

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val typeService = NotificationTypeServiceImpl(NotificationTypeRepositoryImpl())
    private val mappingService = NotificationPreferenceMappingServiceImpl(
        typeService,
        NotificationPreferenceMappingRepositoryImpl(),
        NotificationPreferenceRepositoryImpl(),
    )
    private val preferenceService = NotificationPreferenceServiceImpl(
        typeService,
        mappingService,
        NotificationPreferenceRepositoryImpl(),
        NotificationSettingsRepositoryImpl(),
        UnsubscribeTokenRepositoryImpl(),
    )
    private val deliveryTracking = DeliveryTrackingServiceImpl(
        DeliveryEventRepositoryImpl(),
        DeliveryStatusRepositoryImpl(),
        SuppressionListRepositoryImpl(),
    )

    /** Fixed "now": 2026-07-07 23:30 in Chicago (inside a 22:00–07:00 window). */
    private var fixedNow: ZonedDateTime =
        ZonedDateTime.of(2026, 7, 7, 23, 30, 0, 0, ZoneId.of("America/Chicago"))

    private val gate = object : NotificationPreferenceGateImpl(preferenceService, deliveryTracking) {
        override fun now(zone: ZoneId): ZonedDateTime = fixedNow.withZoneSameInstant(zone)
    }

    private val profileId: UUID = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

        if (!schemaInitialized) {
            runBlocking {
                // Stage 1: the schema exactly as production had it before this work.
                FlywayMigration(pool).migrate(listOf(CommunicationsV1Migration()))
            }
            withDb {
                transaction {
                    connection().useStatement(
                        """
                        INSERT INTO communications.email_preferences (profile_id, category, opted_out, unsubscribe_token, updated_at) VALUES
                            ('$LEGACY_A', 'marketing',  true,  'legacy-tok-marketing',  '$LEGACY_UPDATED_AT'),
                            ('$LEGACY_A', 'digest',     false, NULL,                    '$LEGACY_UPDATED_AT'),
                            ('$LEGACY_A', 'newsletter', true,  'legacy-tok-newsletter', '$LEGACY_UPDATED_AT'),
                            ('$LEGACY_B', 'digest',     true,  'legacy-tok-b-digest',   '$LEGACY_UPDATED_AT');

                        INSERT INTO communications.delivery_events (
                            id, message_id, recipient_id, channel, status,
                            provider_event, error_code, error_message, metadata, created_at
                        ) VALUES (
                            '$LEGACY_DELIVERY_EVENT',
                            '$LEGACY_DELIVERY_MESSAGE',
                            '$LEGACY_DELIVERY_RECIPIENT',
                            'PUSH',
                            'OPENED',
                            'legacy-open',
                            'legacy-event-code',
                            'legacy event message',
                            '{"source":"legacy"}',
                            '$LEGACY_DELIVERY_CREATED'
                        );

                        INSERT INTO communications.delivery_status (
                            message_id, recipient_id, channel, status, attempts,
                            last_attempt_at, delivered_at, opened_at, clicked_at,
                            error_code, error_message, created_at, updated_at
                        ) VALUES (
                            '$LEGACY_DELIVERY_MESSAGE',
                            '$LEGACY_DELIVERY_RECIPIENT',
                            'PUSH',
                            'CLICKED',
                            3,
                            '$LEGACY_DELIVERY_CREATED',
                            '$LEGACY_DELIVERY_CREATED',
                            '$LEGACY_DELIVERY_CREATED',
                            '$LEGACY_DELIVERY_UPDATED',
                            'legacy-status-code',
                            'legacy status message',
                            '$LEGACY_DELIVERY_CREATED',
                            '$LEGACY_DELIVERY_UPDATED'
                        )
                        """.trimIndent()
                    ) { it.execute() }
                }
            }
            runBlocking {
                // Stage 2: the real upgrade — Flyway applies every remaining migration on top.
                FlywayMigration(pool).migrate(listOf(CommunicationsMigration()))
            }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement(
                    """
                    DELETE FROM communications.notification_preferences WHERE profile_id NOT IN ('$LEGACY_A', '$LEGACY_B');
                    DELETE FROM communications.notification_preference_mappings;
                    DELETE FROM communications.notification_settings;
                    DELETE FROM communications.unsubscribe_tokens WHERE token NOT LIKE 'legacy-%';
                    DELETE FROM communications.suppression_list;
                    DELETE FROM communications.delivery_events
                    WHERE message_id <> '$LEGACY_DELIVERY_MESSAGE';
                    DELETE FROM communications.delivery_status
                    WHERE message_id <> '$LEGACY_DELIVERY_MESSAGE';
                    DELETE FROM communications.notification_types
                    WHERE system = false
                      AND key NOT IN ('digest', 'marketing', 'newsletter');
                    """.trimIndent()
                ) { it.execute() }
            }
        }
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val mgr = pool.connection()
        try {
            withContext(mgr.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { mgr.release() }
        }
    }

    /**
     * The V2 migration carries every legacy row forward losslessly:
     * protected system types and deletable optional defaults have the
     * right flags, the unknown legacy category is promoted to a deletable
     * custom type, EMAIL-channel preference rows preserve opt-outs and
     * timestamps, and tokens move to the token table with their scope.
     */
    @Test
    fun `migration carries legacy email preferences forward losslessly`() = withDb {
        val types = typeService.list().associateBy { it.key }
        assertEquals(false, types.getValue(NotificationTypeKeys.TRANSACTIONAL).optional)
        assertEquals(true, types.getValue(NotificationTypeKeys.TRANSACTIONAL).system)
        assertEquals(false, types.getValue(NotificationTypeKeys.SECURITY).optional)
        assertEquals(true, types.getValue(NotificationTypeKeys.DIGEST).optional)
        assertTrue(types.values.all { it.defaultEmailEnabled && it.defaultPushEnabled })
        assertEquals(false, types.getValue(NotificationTypeKeys.DIGEST).system)
        assertEquals(true, types.getValue(NotificationTypeKeys.MARKETING).optional)
        assertEquals(false, types.getValue(NotificationTypeKeys.MARKETING).system)
        assertEquals(true, types.getValue(NotificationTypeKeys.GIT_ACTIVITY).optional)
        assertEquals(true, types.getValue(NotificationTypeKeys.GIT_ACTIVITY).system)
        assertEquals(true, types.getValue(NotificationTypeKeys.WORKOPS_ACTIVITY).optional)
        assertEquals(true, types.getValue(NotificationTypeKeys.WORKOPS_ACTIVITY).system)
        val newsletter = types.getValue("newsletter")
        assertEquals(true, newsletter.optional)
        assertEquals(false, newsletter.system)
        assertEquals("Newsletter", newsletter.name)

        val prefsA = preferenceService.getPreferences(LEGACY_A).associateBy { it.channel to it.type }
        assertTrue(prefsA.getValue(DeliveryChannel.EMAIL to NotificationTypeKeys.MARKETING).optedOut)
        assertFalse(prefsA.getValue(DeliveryChannel.EMAIL to NotificationTypeKeys.DIGEST).optedOut)
        assertTrue(prefsA.getValue(DeliveryChannel.EMAIL to "newsletter").optedOut)
        // The channel dimension is new: nothing migrated onto PUSH.
        assertFalse(prefsA.getValue(DeliveryChannel.PUSH to NotificationTypeKeys.MARKETING).optedOut)
        assertEquals(
            LEGACY_UPDATED_AT.toInstant(),
            prefsA.getValue(DeliveryChannel.EMAIL to NotificationTypeKeys.MARKETING).updatedAt.toInstant(),
        )
        val prefsB = preferenceService.getPreferences(LEGACY_B).associateBy { it.channel to it.type }
        assertTrue(prefsB.getValue(DeliveryChannel.EMAIL to NotificationTypeKeys.DIGEST).optedOut)

        // Tokens moved to the token table with their type scope; the
        // NULL-token legacy row contributed nothing.
        assertEquals(LEGACY_A, preferenceService.profileIdForToken("legacy-tok-marketing"))
        assertEquals(LEGACY_A, preferenceService.profileIdForToken("legacy-tok-newsletter"))
        assertEquals(LEGACY_B, preferenceService.profileIdForToken("legacy-tok-b-digest"))
    }

    @Test
    fun `communications enums are lowercase`() = withDb {
        val labels = connection().useStatement(
            """
            SELECT t.typname, e.enumlabel
            FROM pg_type t
            JOIN pg_namespace n ON n.oid = t.typnamespace
            JOIN pg_enum e ON e.enumtypid = t.oid
            WHERE n.nspname = 'communications'
              AND t.typname IN ('channel', 'delivery_status_type')
            ORDER BY t.typname, e.enumsortorder
            """.trimIndent(),
        ) { statement ->
            val result = linkedMapOf<String, MutableList<String>>()
            val rows = statement.executeQuery()
            while (rows.next()) {
                result.getOrPut(rows.getString("typname")) { mutableListOf() }
                    .add(rows.getString("enumlabel"))
            }
            result
        }

        assertEquals(setOf("email", "push"), labels.getValue("channel").toSet())
        assertEquals(
            setOf(
                "pending",
                "sent",
                "delivered",
                "deferred",
                "bounced",
                "dropped",
                "opened",
                "clicked",
                "spam_report",
                "unsubscribed",
                "failed",
            ),
            labels.getValue("delivery_status_type").toSet(),
        )
    }

    @Test
    fun `lowercase enum migration preserves existing delivery rows`() = withDb {
        connection().useStatement(
            """
            SELECT
                channel::text AS channel,
                status::text AS status,
                provider_event,
                error_code,
                error_message,
                metadata,
                created_at
            FROM communications.delivery_events
            WHERE id = '$LEGACY_DELIVERY_EVENT'
            """.trimIndent(),
        ) { statement ->
            val row = statement.executeQuery()
            assertTrue(row.next())
            assertEquals("push", row.getString("channel"))
            assertEquals("opened", row.getString("status"))
            assertEquals("legacy-open", row.getString("provider_event"))
            assertEquals("legacy-event-code", row.getString("error_code"))
            assertEquals("legacy event message", row.getString("error_message"))
            assertEquals("""{"source": "legacy"}""", row.getString("metadata"))
            assertEquals(
                LEGACY_DELIVERY_CREATED.toInstant(),
                row.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
            )
            assertFalse(row.next())
        }

        connection().useStatement(
            """
            SELECT
                channel::text AS channel,
                status::text AS status,
                attempts,
                last_attempt_at,
                delivered_at,
                opened_at,
                clicked_at,
                error_code,
                error_message,
                created_at,
                updated_at
            FROM communications.delivery_status
            WHERE message_id = '$LEGACY_DELIVERY_MESSAGE'
              AND recipient_id = '$LEGACY_DELIVERY_RECIPIENT'
            """.trimIndent(),
        ) { statement ->
            val row = statement.executeQuery()
            assertTrue(row.next())
            assertEquals("push", row.getString("channel"))
            assertEquals("clicked", row.getString("status"))
            assertEquals(3, row.getInt("attempts"))
            assertEquals(
                LEGACY_DELIVERY_CREATED.toInstant(),
                row.getObject("last_attempt_at", java.time.OffsetDateTime::class.java).toInstant(),
            )
            assertEquals(
                LEGACY_DELIVERY_CREATED.toInstant(),
                row.getObject("delivered_at", java.time.OffsetDateTime::class.java).toInstant(),
            )
            assertEquals(
                LEGACY_DELIVERY_CREATED.toInstant(),
                row.getObject("opened_at", java.time.OffsetDateTime::class.java).toInstant(),
            )
            assertEquals(
                LEGACY_DELIVERY_UPDATED.toInstant(),
                row.getObject("clicked_at", java.time.OffsetDateTime::class.java).toInstant(),
            )
            assertEquals("legacy-status-code", row.getString("error_code"))
            assertEquals("legacy status message", row.getString("error_message"))
            assertEquals(
                LEGACY_DELIVERY_CREATED.toInstant(),
                row.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
            )
            assertEquals(
                LEGACY_DELIVERY_UPDATED.toInstant(),
                row.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant(),
            )
            assertFalse(row.next())
        }
    }

    /**
     * System types are protected against deletion at both the
     * service and SQL layers; custom types delete cleanly.
     */
    @Test
    fun `system types are protected and custom types are deletable`() = withDb {
        assertFailsWith<IllegalArgumentException> { typeService.delete(NotificationTypeKeys.TRANSACTIONAL) }
        // The SQL guard holds even if the service check were bypassed.
        assertEquals(0, NotificationTypeRepositoryImpl().delete(NotificationTypeKeys.SECURITY))

        typeService.set("it-order-updates", "Order updates", null, true, true, true, 50, false)
        assertTrue(typeService.delete("it-order-updates"))
        assertNull(typeService.get("it-order-updates"))
    }

    @Test
    fun `hidden notification types persist without changing preference behavior`() = withDb {
        val hidden = typeService.set("it-internal-updates", "Internal updates", null, true, true, true, 55, true)
        assertTrue(hidden.hidden)

        preferenceService.setOptOut(profileId, DeliveryChannel.EMAIL, hidden.key, true)
        assertTrue(preferenceService.isOptedOut(profileId, DeliveryChannel.EMAIL, hidden.key))
        assertTrue(typeService.get(hidden.key)?.hidden == true)
    }

    /**
     * A custom type participates in the effective matrix and its
     * stored preference rows become inert (fail-open) once the
     * type is deleted from the catalog.
     */
    @Test
    fun `custom type appears in matrix and fails open after deletion`() = withDb {
        typeService.set("it-beta-news", "Beta news", "Early features.", true, true, true, 60, false)
        preferenceService.setOptOut(profileId, DeliveryChannel.PUSH, "it-beta-news", true)
        assertTrue(preferenceService.isOptedOut(profileId, DeliveryChannel.PUSH, "it-beta-news"))
        assertNotNull(preferenceService.getPreferences(profileId).find {
            it.channel == DeliveryChannel.PUSH && it.type == "it-beta-news" && it.optedOut
        })

        typeService.delete("it-beta-news")
        assertTrue(preferenceService.getPreferences(profileId).none { it.type == "it-beta-news" })
        assertFalse(preferenceService.isOptedOut(profileId, DeliveryChannel.PUSH, "it-beta-news"))
    }

    @Test
    fun `channel defaults apply independently until stored preferences override them`() = withDb {
        val type = typeService.set(
            "it-announcements",
            "Announcements",
            null,
            true,
            false,
            true,
            65,
            false,
        )
        assertFalse(type.defaultEmailEnabled)
        assertTrue(type.defaultPushEnabled)
        assertFalse(typeService.get(type.key)?.defaultEmailEnabled ?: true)
        assertTrue(typeService.get(type.key)?.defaultPushEnabled ?: false)

        val defaults = preferenceService.getPreferences(profileId)
            .filter { it.type == type.key }
            .associateBy { it.channel }
        assertTrue(defaults.getValue(DeliveryChannel.EMAIL).optedOut)
        assertFalse(defaults.getValue(DeliveryChannel.PUSH).optedOut)

        preferenceService.setOptOut(profileId, DeliveryChannel.EMAIL, type.key, false)
        assertFalse(preferenceService.isOptedOut(profileId, DeliveryChannel.EMAIL, type.key))
        assertFalse(preferenceService.isOptedOut(profileId, DeliveryChannel.PUSH, type.key))
    }

    /**
     * Opt-out upserts round trip through the real SQL, including
     * opting back in on the composite primary key.
     */
    @Test
    fun `preference upsert round trips through real SQL`() = withDb {
        preferenceService.setOptOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST, true)
        assertTrue(preferenceService.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST))
        preferenceService.setOptOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST, false)
        assertFalse(preferenceService.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST))
    }

    @Test
    fun `external mapping round trips and clears local state for its cell`() = withDb {
        val type = "it-hubspot-marketing"
        typeService.set(type, "HubSpot marketing", null, true, true, true, 70, false)
        preferenceService.setOptOut(profileId, DeliveryChannel.EMAIL, type, true)
        assertTrue(preferenceService.isOptedOut(profileId, DeliveryChannel.EMAIL, type))

        val mapping = mappingService.set(
            type,
            DeliveryChannel.EMAIL,
            "hubspot",
            "42",
        )

        assertEquals("42", mapping.externalId)
        assertEquals(mapping, mappingService.get(type, DeliveryChannel.EMAIL))
        assertTrue(mappingService.list().contains(mapping))
        assertNull(
            NotificationPreferenceRepositoryImpl().get(
                profileId,
                DeliveryChannel.EMAIL,
                type,
            ),
        )
        assertTrue(mappingService.delete(type, DeliveryChannel.EMAIL))
        assertFalse(mappingService.delete(type, DeliveryChannel.EMAIL))
        assertTrue(typeService.delete(type))
    }

    /**
     * The gate composes the real suppression list and preference
     * rows, and gate decisions recorded as delivery events read
     * back through the aggregate status (PUSH channel cast included).
     */
    @Test
    fun `gate suppresses via real suppression list and preferences and records events`() = withDb {
        deliveryTracking.suppress("bounced@example.com", "hard_bounce")
        val suppressed = gate.evaluate(profileId, DeliveryChannel.EMAIL, null, "bounced@example.com")
        assertIs<GateDecision.Suppressed>(suppressed)
        assertEquals(GateReasons.SUPPRESSED_ADDRESS, suppressed.reason)

        preferenceService.setOptOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING, true)
        val optedOut = gate.evaluate(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING, "ok@example.com")
        assertIs<GateDecision.Suppressed>(optedOut)
        assertEquals(GateReasons.PREFERENCE, optedOut.reason)

        val messageId = UUID.random()
        deliveryTracking.recordEvent(DeliveryEvent(
            messageId = messageId,
            recipientId = profileId,
            channel = DeliveryChannel.PUSH,
            status = DeliveryStatusType.DROPPED,
            providerEvent = GateReasons.PREFERENCE,
        ))
        // getStatus is EMAIL-scoped; the PUSH-channel aggregate is
        // visible through the per-message listing.
        val status = deliveryTracking.getStatusesForMessage(messageId)
            .find { it.channel == DeliveryChannel.PUSH }
        assertNotNull(status)
        assertEquals(DeliveryStatusType.DROPPED, status.status)
    }

    @Test
    fun `delivery tracking joins an existing transaction`() = withDb {
        val messageId = UUID.random()
        transaction {
            deliveryTracking.recordEvent(
                DeliveryEvent(
                    messageId = messageId,
                    recipientId = profileId,
                    status = DeliveryStatusType.PENDING,
                ),
            )
        }

        assertEquals(
            DeliveryStatusType.PENDING,
            deliveryTracking.getStatus(messageId, profileId)?.status,
        )
    }

    /**
     * Quiet hours stored through the service defer push in the
     * recipient's timezone and release outside the window; security
     * and high-priority push bypass.
     */
    @Test
    fun `quiet hours defer push based on stored settings`() = withDb {
        preferenceService.setQuietHours(profileId, "America/Chicago", "22:00", "07:00")

        val deferred = gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING)
        assertIs<GateDecision.Deferred>(deferred)
        assertEquals(
            fixedNow.plusDays(1).with(LocalTime.of(7, 0)).toOffsetDateTime().toInstant(),
            deferred.until.toInstant(),
        )

        assertEquals(
            GateDecision.Allow,
            gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING, highPriority = true),
        )
        assertEquals(
            GateDecision.Allow,
            gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.SECURITY),
        )

        // Outside the window the same recipient sends immediately.
        fixedNow = ZonedDateTime.of(2026, 7, 7, 12, 0, 0, 0, ZoneId.of("America/Chicago"))
        assertEquals(
            GateDecision.Allow,
            gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING),
        )
    }

    /**
     * The full unsubscribe flow against real SQL: token generation,
     * one-click unsubscribe, and the gate then suppressing email
     * while push stays deliverable.
     */
    @Test
    fun `unsubscribe token flow opts out and the gate enforces it`() = withDb {
        val token = preferenceService.generateUnsubscribeToken(profileId, NotificationTypeKeys.MARKETING)
        assertTrue(preferenceService.unsubscribeByToken(token))

        val email = gate.evaluate(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING, "ok@example.com")
        assertIs<GateDecision.Suppressed>(email)
        assertEquals(GateReasons.PREFERENCE, email.reason)
        assertEquals(
            GateDecision.Allow,
            gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING),
        )
        assertFalse(preferenceService.unsubscribeByToken("no-such-token"))
    }
}
