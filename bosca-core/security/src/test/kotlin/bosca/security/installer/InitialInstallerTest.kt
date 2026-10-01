@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.security.installer

import bosca.di.ProviderRegistry
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.model.SimplePasswordAttributes
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InitialInstallerTest {

    private val installation = mockk<PackageInstallation>()
    private val version = mockk<PackageInstallationVersion>()

    @BeforeTest
    fun setup() = ProviderRegistry.clear()

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `creates configured initial principals groups and profiles`() = runTest {
        val security = mockk<SecurityService>(relaxed = true)
        val profiles = mockk<ProfileService>(relaxed = true)
        val admin = authenticatedPrincipal()
        val serviceAccount = authenticatedPrincipal()
        val adminId = admin.id
        val serviceAccountId = serviceAccount.id
        val administrators = Group(
            id = UUID.random(),
            name = "administrators",
            description = "Administrators",
            type = GroupType.SYSTEM,
        )
        val serviceAccounts = Group(
            id = UUID.random(),
            name = "sa",
            description = "Service accounts",
            type = GroupType.SYSTEM,
        )
        val credentials = mutableListOf<SimplePasswordAttributes>()
        coEvery { security.getPrincipalByIdentifier(any()) } returns null
        coEvery { security.addPrincipal(any(), capture(credentials)) } returnsMany
            listOf(admin, serviceAccount)
        coEvery {
            security.getGroupByName("administrators", GroupType.SYSTEM)
        } returns administrators
        coEvery { security.getGroupByName("sa", GroupType.SYSTEM) } returns serviceAccounts

        InitialInstaller(security, profiles, application(withPasswords = true))
            .install(installation, version)

        assertEquals(listOf("admin", "sa"), credentials.map { it.identifier })
        assertEquals(listOf("admin-password", "sa-password"), credentials.map { it.password })
        coVerify { security.addPrincipalGroup(adminId, administrators.id) }
        coVerify { security.addPrincipalGroup(serviceAccountId, serviceAccounts.id) }
        coVerify(exactly = 2) { profiles.add(any(), any(), any()) }
    }

    @Test
    fun `existing initial principals are left unchanged`() = runTest {
        val security = mockk<SecurityService>()
        val profiles = mockk<ProfileService>()
        coEvery { security.getPrincipalByIdentifier("admin") } returns Principal(id = UUID.random())
        coEvery { security.getPrincipalByIdentifier("sa") } returns Principal(id = UUID.random())

        InitialInstaller(security, profiles, application(withPasswords = false))
            .install(installation, version)

        coVerify(exactly = 0) { security.addPrincipal(any(), any()) }
        coVerify(exactly = 0) { profiles.add(any(), any(), any()) }
    }

    @Test
    fun `generated initial password has strong random length`() = runTest {
        val security = mockk<SecurityService>(relaxed = true)
        val profiles = mockk<ProfileService>(relaxed = true)
        val credentials = slot<SimplePasswordAttributes>()
        coEvery { security.getPrincipalByIdentifier("admin") } returns null
        coEvery { security.getPrincipalByIdentifier("sa") } returns Principal(id = UUID.random())
        coEvery { security.addPrincipal(any(), capture(credentials)) } returns authenticatedPrincipal()
        coEvery {
            security.getGroupByName("administrators", GroupType.SYSTEM)
        } returns Group(
            id = UUID.random(),
            name = "administrators",
            description = "Administrators",
            type = GroupType.SYSTEM,
        )

        InitialInstaller(security, profiles, application(withPasswords = false))
            .install(installation, version)

        assertTrue(credentials.captured.password.length >= 42)
    }

    @Test
    fun `missing required administrator group fails installation`() = runTest {
        val security = mockk<SecurityService>(relaxed = true)
        val profiles = mockk<ProfileService>(relaxed = true)
        coEvery { security.getPrincipalByIdentifier("admin") } returns null
        coEvery { security.addPrincipal(any(), any()) } returns authenticatedPrincipal()
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns null

        assertFailsWith<IllegalStateException> {
            InitialInstaller(security, profiles, application(withPasswords = true))
                .install(installation, version)
        }
    }

    @Test
    fun `missing required service-account group fails installation`() = runTest {
        val security = mockk<SecurityService>(relaxed = true)
        val profiles = mockk<ProfileService>(relaxed = true)
        coEvery { security.getPrincipalByIdentifier("admin") } returns Principal(id = UUID.random())
        coEvery { security.getPrincipalByIdentifier("sa") } returns null
        coEvery { security.addPrincipal(any(), any()) } returns authenticatedPrincipal()
        coEvery { security.getGroupByName("sa", GroupType.SYSTEM) } returns null

        assertFailsWith<IllegalStateException> {
            InitialInstaller(security, profiles, application(withPasswords = true))
                .install(installation, version)
        }
    }

    private fun application(withPasswords: Boolean): BoscaApplication {
        val passwords = if (withPasswords) {
            """
            initialization:
              password:
                admin: admin-password
                sa: sa-password
            """.trimIndent()
        } else {
            ""
        }
        return BoscaApplication(ApplicationConfig.load(passwords.byteInputStream()))
    }

    private fun authenticatedPrincipal(): AuthenticatedPrincipal {
        val principalId = UUID.random()
        return mockk {
            every { id } returns principalId
        }
    }
}
