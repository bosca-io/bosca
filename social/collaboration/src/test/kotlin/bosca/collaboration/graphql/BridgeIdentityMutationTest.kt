package bosca.collaboration.graphql

import bosca.collaboration.bridge.BridgeIdentityMapping
import bosca.collaboration.bridge.BridgePlatform
import bosca.collaboration.bridge.BridgeService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit-tests the bridge identity-mapping admin surface on
 * [BridgeMutationController] and [BridgeController]. Group authorization,
 * input -> service-call wiring, and the boolean return shape are
 * exercised. The full GraphQL roundtrip (schema parse, query execution)
 * is covered by the e2e test harness.
 */
class BridgeIdentityMutationTest {

    private lateinit var bridgeService: BridgeService
    private lateinit var groupEvaluator: GroupEvaluator
    private lateinit var auth: AuthenticationContext

    private val anyPlatform = BridgePlatform.SLACK
    private val anyWorkspaceId = "T01234567"
    private val anyExternalUserId = "U7654321"
    private val anyDisplayName = "Alice Example"
    private val anyEmail = "alice@example.com"

    @BeforeTest
    fun setup() {
        bridgeService = mockk(relaxed = true)
        groupEvaluator = mockk(relaxed = true)
        auth = mockk(relaxed = true)
        // verifyHasAdminGroup throws on missing admin; for the success
        // path we make it a no-op via the relaxed mock.
    }

    private fun mutator() = BridgeMutationController(bridgeService, groupEvaluator)
    private fun query() = BridgeController(bridgeService, groupEvaluator)

    // ---- mapIdentity --------------------------------------------------

    @Test
    fun `mapIdentity passes the input straight through to the service`() = runBlocking {
        val input = MapBridgeIdentityInput(
            platform = anyPlatform,
            externalUserId = anyExternalUserId,
            workspaceId = anyWorkspaceId,
            displayName = anyDisplayName,
            email = anyEmail,
            profileId = UUID.random(),
        )
        val expected = BridgeIdentityMapping(
            id = UUID.random(),
            platform = input.platform,
            externalUserId = input.externalUserId,
            workspaceId = input.workspaceId,
            profileId = input.profileId,
            displayName = input.displayName,
            email = input.email,
        )
        coEvery {
            bridgeService.mapIdentity(
                platform = input.platform,
                externalUserId = input.externalUserId,
                workspaceId = input.workspaceId,
                displayName = input.displayName,
                email = input.email,
                profileId = input.profileId,
            )
        } returns expected

        val result = mutator().mapIdentity(auth, input)
        assertEquals(expected, result)
    }

    @Test
    fun `mapIdentity verifies admin group before calling the service`() = runBlocking {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        val input = MapBridgeIdentityInput(
            platform = anyPlatform,
            externalUserId = anyExternalUserId,
            workspaceId = anyWorkspaceId,
            displayName = anyDisplayName,
        )
        assertFailsWith<SecurityException> {
            runBlocking { mutator().mapIdentity(auth, input) }
        }
        coVerify(exactly = 0) { bridgeService.mapIdentity(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `mapIdentity tolerates a null profile id (unmapped record)`() = runBlocking {
        val input = MapBridgeIdentityInput(
            platform = anyPlatform,
            externalUserId = anyExternalUserId,
            workspaceId = anyWorkspaceId,
            displayName = anyDisplayName,
            email = null,
            profileId = null,
        )
        coEvery {
            bridgeService.mapIdentity(
                platform = input.platform,
                externalUserId = input.externalUserId,
                workspaceId = input.workspaceId,
                displayName = input.displayName,
                email = null,
                profileId = null,
            )
        } returns BridgeIdentityMapping(
            id = UUID.random(),
            platform = input.platform,
            externalUserId = input.externalUserId,
            workspaceId = input.workspaceId,
            profileId = null,
            displayName = input.displayName,
            email = null,
        )
        val result = mutator().mapIdentity(auth, input)
        assertEquals(input.externalUserId, result.externalUserId)
        // Caller didn't provide a profile id; the service-mapped row
        // also doesn't have one.
        kotlin.test.assertNull(result.profileId)
    }

    // ---- deleteIdentity -----------------------------------------------

    @Test
    fun `deleteIdentity returns true and forwards the id`() = runBlocking {
        val id = UUID.random()
        assertTrue(mutator().deleteIdentity(auth, id))
        coVerify(exactly = 1) { bridgeService.deleteIdentity(id) }
    }

    @Test
    fun `deleteIdentity verifies admin group`() = runBlocking {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        assertFailsWith<SecurityException> {
            runBlocking { mutator().deleteIdentity(auth, UUID.random()) }
        }
        coVerify(exactly = 0) { bridgeService.deleteIdentity(any()) }
    }

    // ---- identities list query ---------------------------------------

    @Test
    fun `identities query forwards platform and workspaceId to the service`() = runBlocking {
        val expected = listOf(
            BridgeIdentityMapping(
                id = UUID.random(),
                platform = anyPlatform,
                externalUserId = anyExternalUserId,
                workspaceId = anyWorkspaceId,
                profileId = UUID.random(),
                displayName = anyDisplayName,
                email = anyEmail,
            ),
        )
        coEvery { bridgeService.listIdentities(anyPlatform, anyWorkspaceId) } returns expected
        val result = query().identities(auth, anyPlatform, anyWorkspaceId)
        assertEquals(expected, result)
    }

    @Test
    fun `identities query verifies admin group`() = runBlocking {
        every { groupEvaluator.verifyHasAdminGroup(auth) } throws SecurityException("not admin")
        assertFailsWith<SecurityException> {
            runBlocking { query().identities(auth, anyPlatform, anyWorkspaceId) }
        }
        coVerify(exactly = 0) { bridgeService.listIdentities(any(), any()) }
    }

    @Test
    fun `identities query returns an empty list when the service has no records`() = runBlocking {
        coEvery { bridgeService.listIdentities(anyPlatform, anyWorkspaceId) } returns emptyList()
        val result = query().identities(auth, anyPlatform, anyWorkspaceId)
        assertTrue(result.isEmpty())
    }
}
