package bosca.analytics.gradle

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsGradlePluginTest {
    @Test
    fun `plugin registers defaults and compiler coordinates`() {
        val project = ProjectBuilder.builder().build()
        val plugin = AnalyticsGradlePlugin()

        plugin.apply(project)

        val extension = project.extensions.getByType(AnalyticsExtension::class.java)
        assertTrue(extension.enabled.get())
        assertFalse(extension.verbose.get())
        assertEquals("bosca.analytics", plugin.getCompilerPluginId())
        assertEquals("io.bosca", plugin.getPluginArtifact().groupId)
        assertEquals("analytics-compiler", plugin.getPluginArtifact().artifactId)
        assertTrue(!plugin.getPluginArtifact().version.isNullOrBlank())
    }
}
