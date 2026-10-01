package bosca.profile.attribute.graphql

import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.model.ProfileVisibility
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileAttributeTypesControllerTest {

    private val groupEvaluator = mockk<GroupEvaluator>()
    private val service = mockk<ProfileAttributeService>()
    private val controller = ProfileAttributeTypesController(groupEvaluator, service)

    private fun createType(id: String, visibility: ProfileVisibility): ProfileAttributeType {
        return ProfileAttributeType(
            id = id,
            name = id,
            description = "Description for $id",
            visibility = visibility,
            protected = false
        )
    }

    @Test
    fun `all returns user-visible types for non-admin`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val publicType = createType("public.type", ProfileVisibility.PUBLIC)
        val userType = createType("user.type", ProfileVisibility.USER)
        val systemType = createType("system.type", ProfileVisibility.SYSTEM)

        coEvery { service.getAllAttributeTypes() } returns listOf(publicType, userType, systemType)
        every { groupEvaluator.hasAdminGroup(authentication) } returns false

        val result = controller.all(authentication)

        assertEquals(listOf(publicType, userType), result)
    }

    @Test
    fun `all returns all types for admin`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val publicType = createType("public.type", ProfileVisibility.PUBLIC)
        val userType = createType("user.type", ProfileVisibility.USER)
        val systemType = createType("system.type", ProfileVisibility.SYSTEM)

        coEvery { service.getAllAttributeTypes() } returns listOf(publicType, userType, systemType)
        every { groupEvaluator.hasAdminGroup(authentication) } returns true

        val result = controller.all(authentication)

        assertEquals(3, result.size)
    }

    @Test
    fun `all returns empty list when no types exist`() = runTest {
        val authentication = mockk<AuthenticationContext>()

        coEvery { service.getAllAttributeTypes() } returns emptyList()
        every { groupEvaluator.hasAdminGroup(authentication) } returns false

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
    }
}
