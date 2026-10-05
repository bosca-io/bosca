package bosca.community.ai.configuration

import bosca.community.configuration.JobQueueNames
import bosca.installer.model.PackageInstallation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunityAIConfigurationTest {

    private val config = CommunityAIConfiguration()

    @Test
    fun `communityAiPackage returns package with correct key`() {
        val pkg = config.communityAiPackage()
        assertEquals("community-ai", pkg.key)
    }

    @Test
    fun `communityAiPackage returns package with correct name`() {
        val pkg = config.communityAiPackage()
        assertEquals("Community AI", pkg.name)
    }

    @Test
    fun `communityAiPackage has exactly one version`() {
        val pkg = config.communityAiPackage()
        assertEquals(1, pkg.versions.size)
    }

    @Test
    fun `communityAiPackage version is 1_1_0`() {
        val pkg = config.communityAiPackage()
        assertEquals("1.1.0", pkg.versions.first().version)
    }

    @Test
    fun `communityAiPackage version references quiet-companion installer`() {
        val pkg = config.communityAiPackage()
        val version = pkg.versions.first()
        assertTrue(version.installerNames.contains("quiet-companion"))
    }

    @Test
    fun `communityAiPackage version has exactly one installer name`() {
        val pkg = config.communityAiPackage()
        val version = pkg.versions.first()
        assertEquals(1, version.installerNames.size)
    }
}
