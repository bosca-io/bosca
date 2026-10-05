package bosca.communications.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies default values, equality, and copy behavior for the push
 * notification configuration hierarchy: [PushConfiguration],
 * [FcmConfiguration], and [ApnsConfiguration].
 */
class PushConfigurationTest {

    @Test
    fun pushConfiguration_usesConfigurationServiceKey() {
        assertEquals("push", PushConfiguration.KEY)
    }

    /**
     * A default [PushConfiguration] should be disabled with no
     * platform-specific sub-configurations, matching the safe-by-default
     * principle where push is opt-in.
     */
    @Test
    fun pushConfiguration_defaultsToDisabledWithNullSubConfigs() {
        val config = PushConfiguration()
        assertFalse(config.enabled)
        assertNull(config.fcm)
        assertNull(config.apns)
    }

    /**
     * Structural equality must hold for [PushConfiguration] instances
     * with identical field values.
     */
    @Test
    fun pushConfiguration_equalityForIdenticalInstances() {
        val a = PushConfiguration(enabled = true, fcm = FcmConfiguration("key.json"))
        val b = PushConfiguration(enabled = true, fcm = FcmConfiguration("key.json"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /**
     * Copying a [PushConfiguration] should change only the specified
     * fields while preserving the rest unchanged.
     */
    @Test
    fun pushConfiguration_copySetsSpecifiedFields() {
        val original = PushConfiguration(enabled = false)
        val copied = original.copy(enabled = true)
        assertTrue(copied.enabled)
        assertNull(copied.fcm)
        assertNull(copied.apns)
    }

    /**
     * A default [FcmConfiguration] should have a null service account
     * JSON, indicating Application Default Credentials will be used.
     */
    @Test
    fun fcmConfiguration_defaultsToNullServiceAccountJson() {
        val config = FcmConfiguration()
        assertNull(config.serviceAccountJson)
    }

    /**
     * Structural equality must hold for [FcmConfiguration] instances
     * with the same service account JSON value.
     */
    @Test
    fun fcmConfiguration_equalityForIdenticalInstances() {
        val a = FcmConfiguration(serviceAccountJson = "{}")
        val b = FcmConfiguration(serviceAccountJson = "{}")
        assertEquals(a, b)
    }

    /**
     * A default [ApnsConfiguration] should have null credentials and
     * sandbox mode enabled, matching the safe development-first default.
     */
    @Test
    fun apnsConfiguration_defaultsToNullCredentialsAndSandboxEnabled() {
        val config = ApnsConfiguration()
        assertNull(config.teamId)
        assertNull(config.keyId)
        assertNull(config.bundleId)
        assertNull(config.privateKey)
        assertTrue(config.sandbox)
        assertFalse(config.isConfigured)
    }

    /**
     * Structural equality must hold for [ApnsConfiguration] instances
     * with identical credential and environment values.
     */
    @Test
    fun apnsConfiguration_equalityForIdenticalInstances() {
        val a = ApnsConfiguration(
            teamId = "TEAM123",
            keyId = "KEY456",
            bundleId = "com.example.app",
            privateKey = "PEM_KEY",
            sandbox = false
        )
        val b = ApnsConfiguration(
            teamId = "TEAM123",
            keyId = "KEY456",
            bundleId = "com.example.app",
            privateKey = "PEM_KEY",
            sandbox = false
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.isConfigured)
    }

    /**
     * Copying an [ApnsConfiguration] should allow switching from sandbox
     * to production while preserving all credential fields.
     */
    @Test
    fun apnsConfiguration_copySwitchesToProduction() {
        val sandbox = ApnsConfiguration(
            teamId = "TEAM",
            keyId = "KEY",
            bundleId = "com.app",
            privateKey = "PK",
            sandbox = true
        )
        val production = sandbox.copy(sandbox = false)
        assertFalse(production.sandbox)
        assertEquals("TEAM", production.teamId)
        assertEquals("KEY", production.keyId)
    }

    /**
     * A fully populated [PushConfiguration] should carry both FCM and
     * APNs sub-configurations when dual-platform delivery is required.
     */
    @Test
    fun pushConfiguration_withBothFcmAndApns() {
        val config = PushConfiguration(
            enabled = true,
            fcm = FcmConfiguration("service.json"),
            apns = ApnsConfiguration(teamId = "T", keyId = "K", bundleId = "B", privateKey = "P")
        )
        assertTrue(config.enabled)
        assertEquals("service.json", config.fcm?.serviceAccountJson)
        assertEquals("T", config.apns?.teamId)
    }
}
