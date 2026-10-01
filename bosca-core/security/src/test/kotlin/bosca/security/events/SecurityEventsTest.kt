package bosca.security.events

import bosca.security.model.CredentialType
import bosca.security.model.GroupType
import bosca.serialization.UUID
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class SecurityEventsTest {

    private val principalId = UUID.random()
    private val otherId = UUID.random()

    @Test
    fun `all security events serialize and deserialize`() {
        assertPrincipal(roundTrip(PrincipalCreated(principalId, true), PrincipalCreated.serializer()))
        assertPrincipal(roundTrip(PrincipalUpdated(principalId), PrincipalUpdated.serializer()))
        assertPrincipal(roundTrip(PrincipalMarkedDeleted(principalId), PrincipalMarkedDeleted.serializer()))
        assertPrincipal(roundTrip(PrincipalRestored(principalId), PrincipalRestored.serializer()))
        assertPrincipal(roundTrip(PrincipalDeleted(principalId), PrincipalDeleted.serializer()))
        assertPrincipal(roundTrip(EmailVerified(principalId), EmailVerified.serializer()))
        assertPrincipal(roundTrip(PrincipalSignedIn(principalId, "password"), PrincipalSignedIn.serializer()))
        val revoked = roundTrip(PrincipalLoginsRevoked(principalId, 42), PrincipalLoginsRevoked.serializer())
        assertPrincipal(revoked)
        assertEquals(42, revoked.loginId)
        assertPrincipal(roundTrip(PasswordChanged(principalId), PasswordChanged.serializer()))
        assertPrincipal(roundTrip(PasswordResetRequested(principalId), PasswordResetRequested.serializer()))
        assertPrincipal(roundTrip(PasswordReset(principalId), PasswordReset.serializer()))
        assertPrincipal(
            roundTrip(
                CredentialLinked(principalId, CredentialType.PASSWORD),
                CredentialLinked.serializer(),
            )
        )
        assertPrincipal(
            roundTrip(
                CredentialDeleted(principalId, CredentialType.OAUTH2),
                CredentialDeleted.serializer(),
            )
        )
        assertPrincipal(roundTrip(PasskeyAdded(principalId), PasskeyAdded.serializer()))
        assertPrincipal(
            roundTrip(
                PrincipalAddedToGroup(principalId, otherId),
                PrincipalAddedToGroup.serializer(),
            )
        )
        assertPrincipal(
            roundTrip(
                PrincipalRemovedFromGroup(principalId, otherId),
                PrincipalRemovedFromGroup.serializer(),
            )
        )

        val merged = roundTrip(PrincipalsMerged(principalId, otherId), PrincipalsMerged.serializer())
        assertEquals(principalId, merged.survivorId)
        assertEquals(otherId, merged.duplicateId)

        val created = roundTrip(
            GroupCreated(otherId, "administrators", GroupType.SYSTEM),
            GroupCreated.serializer(),
        )
        assertEquals(otherId, created.groupId)
        assertEquals("administrators", created.name)
        assertEquals(GroupType.SYSTEM, created.type)

        val updated = roundTrip(
            GroupUpdated(otherId, "editors", GroupType.SYSTEM),
            GroupUpdated.serializer(),
        )
        assertEquals(otherId, updated.groupId)
        assertEquals("editors", updated.name)
        assertEquals(GroupType.SYSTEM, updated.type)

        assertEquals(otherId, roundTrip(GroupDeleted(otherId), GroupDeleted.serializer()).groupId)
    }

    @Test
    fun `identity keys use the affected entity`() {
        assertEquals(principalId, PrincipalUpdated(principalId).identityKey())
        assertEquals(otherId, GroupUpdated(otherId, "editors", GroupType.SYSTEM).identityKey())
    }

    @Test
    fun `security email events serialize defaults and value semantics`() {
        val recipients = setOf(principalId)
        val detail = SecurityEmailDetail("Browser", "Safari")
        assertEquals(detail, roundTrip(detail, SecurityEmailDetail.serializer()))
        assertEquals(detail, detail)
        assertNotEquals(detail, detail.copy(label = "Device"))
        assertNotEquals(detail, detail.copy(value = "Firefox"))
        assertFalse(detail.equals("not a detail"))

        val welcome = WelcomeEmailRequested(recipients, "https://app.test/start")
        assertEquals(welcome, roundTrip(welcome, WelcomeEmailRequested.serializer()))
        assertNotEquals(welcome, welcome.copy(recipientIds = setOf(otherId)))
        assertNotEquals(welcome, welcome.copy(getStartedUrl = "https://app.test/other"))

        val verification = EmailVerificationRequested(recipients, "https://app.test/verify", "30 minutes")
        assertEquals(verification, roundTrip(verification, EmailVerificationRequested.serializer()))
        assertNotEquals(verification, verification.copy(recipientIds = setOf(otherId)))
        assertNotEquals(verification, verification.copy(verifyUrl = "https://app.test/other"))
        assertNotEquals(verification, verification.copy(expiresIn = "15 minutes"))
        val verificationWithDefault = Json.decodeFromString(
            EmailVerificationRequested.serializer(),
            """{"recipientIds":["$principalId"],"verifyUrl":"https://app.test/verify"}""",
        )
        assertEquals("", verificationWithDefault.expiresIn)

        val reset = PasswordResetEmailRequested(recipients, "https://app.test/reset", "30 minutes")
        assertEquals(reset, roundTrip(reset, PasswordResetEmailRequested.serializer()))
        assertNotEquals(reset, reset.copy(recipientIds = setOf(otherId)))
        assertNotEquals(reset, reset.copy(resetUrl = "https://app.test/other"))
        assertNotEquals(reset, reset.copy(expiresIn = "15 minutes"))
        val resetWithDefault = Json.decodeFromString(
            PasswordResetEmailRequested.serializer(),
            """{"recipientIds":["$principalId"],"resetUrl":"https://app.test/reset"}""",
        )
        assertEquals("", resetWithDefault.expiresIn)

        val link = AccountLinkEmailRequested(recipients, "https://app.test/link")
        assertEquals(link, roundTrip(link, AccountLinkEmailRequested.serializer()))
        assertNotEquals(link, link.copy(recipientIds = setOf(otherId)))
        assertNotEquals(link, link.copy(confirmUrl = "https://app.test/other"))

        val alert = SecurityAlertEmailRequested(
            recipients,
            "password_changed",
            "now",
            listOf(detail),
            "https://app.test/security",
        )
        assertEquals(alert, roundTrip(alert, SecurityAlertEmailRequested.serializer()))
        assertNotEquals(alert, alert.copy(recipientIds = setOf(otherId)))
        assertNotEquals(alert, alert.copy(event = "credential_linked"))
        assertNotEquals(alert, alert.copy(time = "later"))
        assertNotEquals(alert, alert.copy(details = emptyList()))
        assertNotEquals(alert, alert.copy(reviewUrl = "https://app.test/other"))
        val alertWithDefault = Json.decodeFromString(
            SecurityAlertEmailRequested.serializer(),
            """{"recipientIds":["$principalId"],"event":"password_changed","time":"now","reviewUrl":"https://app.test/security"}""",
        )
        assertEquals(emptyList(), alertWithDefault.details)

        assertEquals(recipients, (welcome as SecurityEmailEvent).recipientIds)
    }

    @Test
    fun `login revocation event preserves the legacy all-logins marker`() {
        val event = PrincipalLoginsRevoked(principalId)

        assertEquals(null, roundTrip(event, PrincipalLoginsRevoked.serializer()).loginId)
        val decoded = Json.decodeFromString(
            PrincipalLoginsRevoked.serializer(),
            """{"principalId":"$principalId"}""",
        )
        assertEquals(null, decoded.loginId)
    }

    @Test
    fun `event serializers reject missing required properties`() {
        assertMissing(PrincipalCreated.serializer())
        assertMissing(PrincipalUpdated.serializer())
        assertMissing(PrincipalMarkedDeleted.serializer())
        assertMissing(PrincipalRestored.serializer())
        assertMissing(PrincipalDeleted.serializer())
        assertMissing(PrincipalsMerged.serializer())
        assertMissing(EmailVerified.serializer())
        assertMissing(PrincipalSignedIn.serializer())
        assertMissing(PrincipalLoginsRevoked.serializer())
        assertMissing(PasswordChanged.serializer())
        assertMissing(PasswordResetRequested.serializer())
        assertMissing(PasswordReset.serializer())
        assertMissing(CredentialLinked.serializer())
        assertMissing(CredentialDeleted.serializer())
        assertMissing(PasskeyAdded.serializer())
        assertMissing(GroupCreated.serializer())
        assertMissing(GroupUpdated.serializer())
        assertMissing(GroupDeleted.serializer())
        assertMissing(PrincipalAddedToGroup.serializer())
        assertMissing(PrincipalRemovedFromGroup.serializer())
    }

    private fun assertPrincipal(event: PrincipalEvent) {
        assertIs<PrincipalEvent>(event)
        assertEquals(principalId, event.principalId)
    }

    private fun <T> roundTrip(value: T, serializer: KSerializer<T>): T =
        Json.decodeFromString(serializer, Json.encodeToString(serializer, value))

    private fun <T> assertMissing(serializer: DeserializationStrategy<T>) {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(serializer, "{}")
        }
    }
}
