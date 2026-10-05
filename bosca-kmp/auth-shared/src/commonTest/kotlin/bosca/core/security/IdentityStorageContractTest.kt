package bosca.core.security

import bosca.core.security.model.Credential
import bosca.core.security.model.Identity
import bosca.core.security.model.Principal
import bosca.core.security.model.Profile
import bosca.core.security.model.ProfileAttribute
import bosca.core.security.type.CredentialType
import bosca.core.security.type.ProfileType
import bosca.core.security.type.ProfileVisibility
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class IdentityStorageContractTest {

    @Test
    fun identity_roundTripsCompletePrincipalAndProfiles() = runTest {
        val storage = FakeTokenStorage()
        val principalId = Uuid.fromLongs(0, 1)
        val profileId = Uuid.fromLongs(0, 2)
        val identity = Identity(
            principal = Principal(
                id = principalId,
                verified = true,
                primaryProfileId = profileId,
                credentials = listOf(Credential("person@example.com", CredentialType.PASSWORD)),
            ),
            profiles = listOf(
                Profile(
                    id = profileId,
                    name = "Offline Person",
                    type = ProfileType.GENERIC,
                    visibility = ProfileVisibility.FRIENDS,
                    slug = "offline-person",
                    isPrimary = true,
                    attributes = listOf(
                        ProfileAttribute(
                            typeId = "timezone",
                            attributes = JsonPrimitive("America/Chicago"),
                            source = "user",
                            priority = 1,
                            confidence = 100,
                            visibility = ProfileVisibility.USER,
                        ),
                    ),
                ),
            ),
        )

        assertNull(storage.getIdentity())
        storage.setIdentity(identity)

        assertEquals(identity, storage.getIdentity())
    }

    @Test
    fun corruptIdentity_isIgnored() = runTest {
        val storage = FakeTokenStorage()
        storage.store["identity"] = "not-json"

        assertNull(storage.getIdentity())
    }

    @Test
    fun settingNull_removesIdentity() = runTest {
        val storage = FakeTokenStorage()
        storage.setIdentity(
            Identity(Principal(Uuid.fromLongs(0, 1), true, null), emptyList()),
        )

        storage.setIdentity(null)

        assertNull(storage.getIdentity())
    }
}
