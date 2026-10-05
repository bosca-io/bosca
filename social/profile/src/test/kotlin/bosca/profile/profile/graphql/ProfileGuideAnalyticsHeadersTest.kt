package bosca.profile.profile.graphql

import bosca.analytics.server.analyticsContext
import bosca.analytics.server.AnalyticsContext
import bosca.analytics.server.AnalyticsMiddleware
import bosca.profile.guide.service.ProfileGuideService
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileGuideAnalyticsHeadersTest {
    @Test
    fun `guide mutation carries request app and installation headers into analytics context`() = runTest {
        val id = UUID.random()
        val profile = mockk<Profile> { every { this@mockk.id } returns id }
        val profiles = mockk<ProfileService>()
        coEvery { profiles.getById(id) } returns profile
        val permissions = mockk<ProfilePermissionEvaluator>()
        coEvery { permissions.isAllowed(any<AuthenticationContext>(), profile, PermissionAction.EDIT) } returns true
        coEvery { permissions.verifyAllowed(any(), profile, PermissionAction.EDIT) } returns Unit
        val guides = mockk<ProfileGuideService>()
        var context = AnalyticsContext()
        coEvery { guides.addProgress(id, any(), 1, 10, any()) } coAnswers {
            context = analyticsContext()
            null
        }
        val controller = ProfileMutationController(mockk(), mockk(), guides, mockk(), permissions,
            mockk(), mockk(), profiles)
        val call = mockk<ServerCall>(relaxed = true)
        every { call.attributes } returns java.util.concurrent.ConcurrentHashMap()
        every { call.authenticationContext } returns bosca.server.auth.CallAuthenticationContext()
        every { call.request.headers["X-App-ID"] } returns "client-app"
        every { call.request.headers["X-App-Version"] } returns "42"
        every { call.request.headers["X-Installation-ID"] } returns "client-installation"
        every { call.request.headers["X-BA-Session-ID"] } returns "client-session"
        AnalyticsMiddleware(mockk(), "server").onHandler(call) {
            controller.addProgress(mockk<AuthenticationContext>(), UUID.random(), 1, 10, JsonObject(emptyMap()), id)
        }
        assertEquals("client-app", context.appId)
        assertEquals("42", context.appVersion)
        assertEquals("client-installation", context.installationId)
        assertEquals("client-session", context.sessionId)
        assertEquals(AnalyticsContext(), analyticsContext())
    }
}
