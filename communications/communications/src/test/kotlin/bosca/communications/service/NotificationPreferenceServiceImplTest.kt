@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreference
import bosca.communications.model.NotificationPreferenceMapping
import bosca.communications.model.NotificationSettings
import bosca.communications.model.NotificationType
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.model.UnsubscribeToken
import bosca.communications.repository.NotificationPreferenceRepository
import bosca.communications.repository.NotificationSettingsRepository
import bosca.communications.repository.UnsubscribeTokenRepository
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies preference matrix defaults, opt-out guardrails for
 * non-optional types, quiet-hours validation, and the unsubscribe
 * token flow in [NotificationPreferenceServiceImpl].
 */
class NotificationPreferenceServiceImplTest {

    private val profileId = UUID.random()
    private val types = FakeNotificationTypes(
        NotificationType(key = NotificationTypeKeys.TRANSACTIONAL, name = "Account activity", optional = false, system = true, displayOrder = 0),
        NotificationType(key = NotificationTypeKeys.SECURITY, name = "Security alerts", optional = false, system = true, displayOrder = 1),
        NotificationType(key = NotificationTypeKeys.DIGEST, name = "Digests", optional = true, system = false, displayOrder = 2),
        NotificationType(key = NotificationTypeKeys.MARKETING, name = "Product news & offers", optional = true, system = false, displayOrder = 3),
    )
    private val preferences = InMemoryPreferences()
    private val mappings = InMemoryMappings()
    private val settings = InMemorySettings()
    private val tokens = InMemoryTokens()
    private val service = NotificationPreferenceServiceImpl(types, mappings, preferences, settings, tokens)

    @BeforeTest
    fun setUp() = ProviderRegistry.clear()

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    /**
     * A profile with no stored rows still gets the full channel ×
     * type matrix back, using the catalog defaults. This fixture's
     * types are all enabled by default.
     */
    @Test
    fun getPreferences_returnsFullOptedInMatrixWhenNothingStored() = runBlocking {
        val matrix = service.getPreferences(profileId)
        assertEquals(DeliveryChannel.entries.size * 4, matrix.size)
        assertTrue(matrix.none { it.optedOut })
    }

    /**
     * A stored opt-out row replaces its default cell without
     * disturbing the rest of the matrix.
     */
    @Test
    fun getPreferences_mergesStoredRowsIntoMatrix() = runBlocking {
        service.setOptOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING, true)
        val matrix = service.getPreferences(profileId)
        val optedOut = matrix.filter { it.optedOut }
        assertEquals(1, optedOut.size)
        assertEquals(DeliveryChannel.EMAIL, optedOut.single().channel)
        assertEquals(NotificationTypeKeys.MARKETING, optedOut.single().type)
    }

    /**
     * A stored row whose type was deleted from the catalog is
     * omitted from the matrix instead of resurrecting the type.
     */
    @Test
    fun getPreferences_omitsRowsForTypesRemovedFromCatalog() = runBlocking {
        preferences.upsert(profileId, DeliveryChannel.EMAIL, "retired-type", true)
        val matrix = service.getPreferences(profileId)
        assertTrue(matrix.none { it.type == "retired-type" })
    }

    /**
     * An absent row for a type enabled by default is not opted out.
     */
    @Test
    fun isOptedOut_isFalseByDefault() = runBlocking {
        assertFalse(service.isOptedOut(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.DIGEST))
    }

    @Test
    fun channelDefaults_applyIndependentlyUntilAUserExplicitlyChangesThem() = runBlocking {
        val emailDefaultOffType = NotificationType(
            key = "announcements",
            name = "Announcements",
            defaultEmailEnabled = false,
            defaultPushEnabled = true,
        )
        val localPreferences = InMemoryPreferences()
        val localService = NotificationPreferenceServiceImpl(
            FakeNotificationTypes(emailDefaultOffType),
            InMemoryMappings(),
            localPreferences,
            InMemorySettings(),
            InMemoryTokens(),
        )

        val defaults = localService.getPreferences(profileId)
        val defaultsByChannel = defaults.associateBy { it.channel }
        assertTrue(defaultsByChannel.getValue(DeliveryChannel.EMAIL).optedOut)
        assertFalse(defaultsByChannel.getValue(DeliveryChannel.PUSH).optedOut)
        assertTrue(localService.isOptedOut(profileId, DeliveryChannel.EMAIL, emailDefaultOffType.key))
        assertFalse(localService.isOptedOut(profileId, DeliveryChannel.PUSH, emailDefaultOffType.key))

        localService.setOptOut(profileId, DeliveryChannel.EMAIL, emailDefaultOffType.key, false)

        val updated = localService.getPreferences(profileId).associateBy { it.channel }
        assertFalse(updated.getValue(DeliveryChannel.EMAIL).optedOut)
        assertFalse(updated.getValue(DeliveryChannel.PUSH).optedOut)
        assertFalse(localService.isOptedOut(profileId, DeliveryChannel.EMAIL, emailDefaultOffType.key))
    }

    /**
     * Opt-out is scoped to the exact channel + type written; the
     * same type on the other channel stays opted-in.
     */
    @Test
    fun setOptOut_appliesOnlyToTheGivenChannel() = runBlocking {
        service.setOptOut(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING, true)
        assertTrue(service.isOptedOut(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING))
        assertFalse(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING))
    }

    /**
     * Opting back in updates the existing row rather than failing
     * on the primary key.
     */
    @Test
    fun setOptOut_isUpsertSoOptingBackInWorks() = runBlocking {
        service.setOptOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST, true)
        service.setOptOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST, false)
        assertFalse(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST))
    }

    /**
     * Non-optional types reject opt-out writes on every channel.
     */
    @Test
    fun setOptOut_rejectsNonOptionalTypes() {
        for (key in listOf(NotificationTypeKeys.TRANSACTIONAL, NotificationTypeKeys.SECURITY)) {
            for (channel in DeliveryChannel.entries) {
                assertFailsWith<IllegalArgumentException> {
                    runBlocking { service.setOptOut(profileId, channel, key, true) }
                }
            }
        }
    }

    /**
     * Types not in the catalog are rejected rather than silently
     * stored.
     */
    @Test
    fun setOptOut_rejectsUnknownType() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.setOptOut(profileId, DeliveryChannel.EMAIL, "newsletter", true) }
        }
    }

    /**
     * Even if a rogue opt-out row exists for a non-optional type,
     * isOptedOut still answers false — enforcement can never
     * suppress transactional or security notifications.
     */
    @Test
    fun isOptedOut_ignoresStoredRowsForNonOptionalTypes() = runBlocking {
        preferences.upsert(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.SECURITY, true)
        assertFalse(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.SECURITY))
    }

    /**
     * Unknown types fail open (deliver) so a catalog edit can never
     * silently suppress sends that reference a removed type.
     */
    @Test
    fun isOptedOut_failsOpenForUnknownTypes() = runBlocking {
        preferences.upsert(profileId, DeliveryChannel.EMAIL, "retired-type", true)
        assertFalse(service.isOptedOut(profileId, DeliveryChannel.EMAIL, "retired-type"))
    }

    @Test
    fun getPreferences_readsMappedEmailStateFromExternalProvider() = runBlocking {
        mappings.set(NotificationTypeKeys.MARKETING, DeliveryChannel.EMAIL, "hubspot", "42")
        preferences.upsert(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING, false)
        val external = FakeExternalProvider().apply { optOuts["42"] = true }
        provides<ExternalNotificationPreferenceProvider>(singleton = true) { external }

        val matrix = service.getPreferences(profileId).associateBy { it.channel to it.type }

        assertTrue(matrix.getValue(DeliveryChannel.EMAIL to NotificationTypeKeys.MARKETING).optedOut)
        assertFalse(matrix.getValue(DeliveryChannel.PUSH to NotificationTypeKeys.MARKETING).optedOut)
        assertEquals(1, external.reads)
    }

    @Test
    fun setOptOut_writesMappedStateExternallyWithoutCreatingLocalRow() = runBlocking {
        mappings.set(NotificationTypeKeys.MARKETING, DeliveryChannel.EMAIL, "hubspot", "42")
        val external = FakeExternalProvider()
        provides<ExternalNotificationPreferenceProvider>(singleton = true) { external }

        val updated = service.setOptOut(
            profileId,
            DeliveryChannel.EMAIL,
            NotificationTypeKeys.MARKETING,
            true,
        )

        assertTrue(updated.optedOut)
        assertEquals(true, external.optOuts["42"])
        assertNull(preferences.get(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING))
    }

    @Test
    fun isOptedOut_readsMappedStateExternally() = runBlocking {
        mappings.set(NotificationTypeKeys.DIGEST, DeliveryChannel.EMAIL, "hubspot", "7")
        val external = FakeExternalProvider().apply { optOuts["7"] = true }
        provides<ExternalNotificationPreferenceProvider>(singleton = true) { external }

        assertTrue(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST))
    }

    @Test
    fun mappedPreference_defaultsToOptedInWhenProviderHasNoExplicitState() = runBlocking {
        mappings.set(NotificationTypeKeys.MARKETING, DeliveryChannel.EMAIL, "hubspot", "42")
        val external = FakeExternalProvider()
        provides<ExternalNotificationPreferenceProvider>(singleton = true) { external }

        val matrix = service.getPreferences(profileId).associateBy { it.channel to it.type }

        assertFalse(matrix.getValue(DeliveryChannel.EMAIL to NotificationTypeKeys.MARKETING).optedOut)
        assertFalse(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING))
    }

    @Test
    fun mappedPreference_usesTypeDefaultWhenProviderHasNoExplicitState() = runBlocking {
        val defaultOffType = NotificationType(
            key = "announcements",
            name = "Announcements",
            defaultEmailEnabled = false,
        )
        val localMappings = InMemoryMappings().apply {
            set(defaultOffType.key, DeliveryChannel.EMAIL, "hubspot", "42")
        }
        val localPreferences = InMemoryPreferences().apply {
            upsert(profileId, DeliveryChannel.EMAIL, defaultOffType.key, false)
        }
        val localService = NotificationPreferenceServiceImpl(
            FakeNotificationTypes(defaultOffType),
            localMappings,
            localPreferences,
            InMemorySettings(),
            InMemoryTokens(),
        )
        val external = FakeExternalProvider()
        provides<ExternalNotificationPreferenceProvider>(singleton = true) { external }

        val matrix = localService.getPreferences(profileId).associateBy { it.channel }

        assertTrue(matrix.getValue(DeliveryChannel.EMAIL).optedOut)
        assertTrue(localService.isOptedOut(profileId, DeliveryChannel.EMAIL, defaultOffType.key))

        external.optOuts["42"] = false
        assertFalse(localService.isOptedOut(profileId, DeliveryChannel.EMAIL, defaultOffType.key))
    }

    @Test
    fun getPreferences_ignoresExternalMappingsForNonOptionalTypes() = runBlocking {
        mappings.set(NotificationTypeKeys.SECURITY, DeliveryChannel.EMAIL, "missing-provider", "7")

        val matrix = service.getPreferences(profileId).associateBy { it.channel to it.type }

        assertFalse(matrix.getValue(DeliveryChannel.EMAIL to NotificationTypeKeys.SECURITY).optedOut)
    }

    @Test
    fun mappedPreference_requiresItsProvider() = runBlocking<Unit> {
        mappings.set(NotificationTypeKeys.MARKETING, DeliveryChannel.EMAIL, "hubspot", "42")

        assertFailsWith<IllegalStateException> {
            service.getPreferences(profileId)
        }
    }

    /**
     * A profile that never configured settings gets defaults with
     * no quiet hours rather than null.
     */
    @Test
    fun getSettings_returnsDefaultsWhenAbsent() = runBlocking {
        val result = service.getSettings(profileId)
        assertEquals(profileId, result.profileId)
        assertNull(result.timeZone)
        assertNull(result.dndStartLocal)
        assertNull(result.dndEndLocal)
    }

    /**
     * Valid quiet hours persist and read back through getSettings.
     */
    @Test
    fun setQuietHours_persistsValidValues() = runBlocking {
        service.setQuietHours(profileId, "America/Chicago", "22:00", "07:30")
        val result = service.getSettings(profileId)
        assertEquals("America/Chicago", result.timeZone)
        assertEquals("22:00", result.dndStartLocal)
        assertEquals("07:30", result.dndEndLocal)
    }

    /**
     * Quiet hours are all-or-nothing: providing only some of the
     * three values is rejected.
     */
    @Test
    fun setQuietHours_rejectsPartialValues() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.setQuietHours(profileId, "America/Chicago", "22:00", null) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.setQuietHours(profileId, null, "22:00", "07:30") }
        }
    }

    /**
     * All-null clears quiet hours entirely.
     */
    @Test
    fun setQuietHours_allNullClears() = runBlocking {
        service.setQuietHours(profileId, "America/Chicago", "22:00", "07:30")
        service.setQuietHours(profileId, null, null, null)
        assertNull(service.getSettings(profileId).timeZone)
    }

    /**
     * Malformed zone ids and times are rejected with a clear error.
     */
    @Test
    fun setQuietHours_rejectsMalformedValues() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.setQuietHours(profileId, "Mars/Olympus_Mons", "22:00", "07:30") }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.setQuietHours(profileId, "America/Chicago", "25:00", "07:30") }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.setQuietHours(profileId, "America/Chicago", "22:00", "7:30") }
        }
    }

    /**
     * A type-scoped token opts out exactly that type on the email
     * channel and leaves push untouched.
     */
    @Test
    fun unsubscribeByToken_scopedTokenOptsOutThatEmailType() = runBlocking {
        val token = service.generateUnsubscribeToken(profileId, NotificationTypeKeys.MARKETING)
        assertTrue(service.unsubscribeByToken(token))
        assertTrue(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING))
        assertFalse(service.isOptedOut(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING))
        assertFalse(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST))
    }

    /**
     * An unscoped token opts out every optional type on email while
     * non-optional types remain deliverable.
     */
    @Test
    fun unsubscribeByToken_unscopedTokenOptsOutAllOptionalEmailTypes() = runBlocking {
        val token = service.generateUnsubscribeToken(profileId, null)
        assertTrue(service.unsubscribeByToken(token))
        assertTrue(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING))
        assertTrue(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST))
        assertFalse(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.TRANSACTIONAL))
        assertFalse(service.isOptedOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.SECURITY))
    }

    @Test
    fun unsubscribeByToken_writesMappedTypesToTheirProvider() = runBlocking {
        mappings.set(NotificationTypeKeys.MARKETING, DeliveryChannel.EMAIL, "hubspot", "42")
        val external = FakeExternalProvider()
        provides<ExternalNotificationPreferenceProvider>(singleton = true) { external }
        val token = service.generateUnsubscribeToken(profileId, NotificationTypeKeys.MARKETING)

        assertTrue(service.unsubscribeByToken(token))
        assertEquals(true, external.optOuts["42"])
    }

    @Test
    fun unsubscribeByToken_acceptsAStoredTokenForARetiredTypeWithoutWriting() = runBlocking {
        tokens.insert("retired-token", profileId, "retired")

        assertTrue(service.unsubscribeByToken("retired-token"))
        assertTrue(preferences.getByProfileId(profileId).isEmpty())
    }

    /**
     * Unknown tokens are rejected without side effects.
     */
    @Test
    fun unsubscribeByToken_returnsFalseForUnknownToken() = runBlocking {
        assertFalse(service.unsubscribeByToken("not-a-token"))
        assertTrue(service.getPreferences(profileId).none { it.optedOut })
    }

    /**
     * Tokens resolve back to their profile for the manage page,
     * and unknown tokens resolve to null.
     */
    @Test
    fun profileIdForToken_resolvesProfileOrNull() = runBlocking {
        val token = service.generateUnsubscribeToken(profileId, null)
        assertEquals(profileId, service.profileIdForToken(token))
        assertNull(service.profileIdForToken("not-a-token"))
    }

    /**
     * Generated tokens are unique per call and reject unknown type
     * scopes.
     */
    @Test
    fun generateUnsubscribeToken_isUniqueAndValidatesType() = runBlocking<Unit> {
        val first = service.generateUnsubscribeToken(profileId, null)
        val second = service.generateUnsubscribeToken(profileId, null)
        assertNotEquals(first, second)
        assertFailsWith<IllegalArgumentException> {
            service.generateUnsubscribeToken(profileId, "newsletter")
        }
    }

    private class InMemoryPreferences : NotificationPreferenceRepository {
        private val rows = mutableMapOf<Triple<UUID, DeliveryChannel, String>, NotificationPreference>()

        override suspend fun getByProfileId(profileId: UUID): List<NotificationPreference> =
            rows.values.filter { it.profileId == profileId }

        override suspend fun get(
            profileId: UUID,
            channel: DeliveryChannel,
            type: String,
        ): NotificationPreference? =
            rows[Triple(profileId, channel, type)]

        override suspend fun upsert(
            profileId: UUID,
            channel: DeliveryChannel,
            type: String,
            optedOut: Boolean,
        ): NotificationPreference {
            val row = NotificationPreference(profileId, channel, type, optedOut)
            rows[Triple(profileId, channel, type)] = row
            return row
        }

        override suspend fun deleteByChannelAndType(channel: DeliveryChannel, type: String): Int {
            val matching = rows.keys.filter { it.second == channel && it.third == type }
            matching.forEach(rows::remove)
            return matching.size
        }
    }

    private class InMemoryMappings : NotificationPreferenceMappingService {
        private val rows = mutableMapOf<Pair<String, DeliveryChannel>, NotificationPreferenceMapping>()

        override suspend fun list(): List<NotificationPreferenceMapping> =
            rows.values.sortedWith(compareBy({ it.type }, { it.channel.name }))

        override suspend fun get(type: String, channel: DeliveryChannel): NotificationPreferenceMapping? =
            rows[type to channel]

        override suspend fun set(
            type: String,
            channel: DeliveryChannel,
            provider: String,
            externalId: String,
        ): NotificationPreferenceMapping {
            val mapping = NotificationPreferenceMapping(type, channel, provider, externalId)
            rows[type to channel] = mapping
            return mapping
        }

        override suspend fun delete(type: String, channel: DeliveryChannel): Boolean =
            rows.remove(type to channel) != null
    }

    private class FakeExternalProvider : ExternalNotificationPreferenceProvider {
        override val key: String = "hubspot"
        val optOuts = mutableMapOf<String, Boolean>()
        var reads = 0

        override suspend fun getOptOuts(
            profileId: UUID,
            channel: DeliveryChannel,
            externalIds: Set<String>,
        ): Map<String, Boolean> {
            reads++
            return externalIds.mapNotNull { id -> optOuts[id]?.let { id to it } }.toMap()
        }

        override suspend fun setOptOut(
            profileId: UUID,
            channel: DeliveryChannel,
            externalId: String,
            optedOut: Boolean,
        ) {
            optOuts[externalId] = optedOut
        }
    }

    private class InMemorySettings : NotificationSettingsRepository {
        private val rows = mutableMapOf<UUID, NotificationSettings>()

        override suspend fun get(profileId: UUID): NotificationSettings? = rows[profileId]

        override suspend fun upsert(
            profileId: UUID,
            timeZone: String?,
            dndStartLocal: String?,
            dndEndLocal: String?,
        ): NotificationSettings {
            val row = NotificationSettings(profileId, timeZone, dndStartLocal, dndEndLocal)
            rows[profileId] = row
            return row
        }
    }

    private class InMemoryTokens : UnsubscribeTokenRepository {
        private val rows = mutableMapOf<String, UnsubscribeToken>()

        override suspend fun get(token: String): UnsubscribeToken? = rows[token]

        override suspend fun insert(token: String, profileId: UUID, type: String?): UnsubscribeToken {
            val row = UnsubscribeToken(token, profileId, type)
            rows[token] = row
            return row
        }
    }
}

/**
 * In-memory [NotificationTypeService] for tests, shared by the
 * preference service tests above and usable with a custom catalog.
 */
class FakeNotificationTypes(vararg seed: NotificationType) : NotificationTypeService {
    private val rows = seed.associateBy { it.key }.toMutableMap()

    override suspend fun list(): List<NotificationType> =
        rows.values.sortedWith(compareBy({ it.displayOrder }, { it.key }))

    override suspend fun get(key: String): NotificationType? = rows[key]

    override suspend fun set(
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

    override suspend fun delete(key: String): Boolean = rows.remove(key) != null
}
