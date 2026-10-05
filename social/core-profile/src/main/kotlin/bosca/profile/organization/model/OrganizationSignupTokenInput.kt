package bosca.profile.organization.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.*

@Serializable
data class OrganizationSignupTokenInput(val type: OrganizationSignupGroupType) {

    fun toOrganizationSignupToken(organizationId: UUID, groupId: UUID): OrganizationSignupToken {
        return OrganizationSignupToken(
            token = generateSecureToken(),
            organizationId = organizationId,
            groupId = groupId,
            created = java.time.OffsetDateTime.now(),
            expires = java.time.OffsetDateTime.now().plusDays(60)
        )
    }

    private fun generateSecureToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32) // 32 bytes = 256 bits
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}