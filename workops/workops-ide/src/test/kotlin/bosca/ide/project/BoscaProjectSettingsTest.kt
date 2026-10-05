package bosca.ide.project

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class BoscaProjectSettingsTest {
    @Test
    fun `unconfigured projects enable every application profile`() {
        val settings = BoscaProjectSettings(fakeProject())

        assertEquals(linkedSetOf("server-a", "server-b"), settings.effectiveProfileIds(listOf("server-a", "server-b")))
    }

    @Test
    fun `server selection preserves mappings routed through a disabled profile`() {
        val settings = BoscaProjectSettings(fakeProject())
        settings.putMapping(BoscaRepositoryMapping("file:///a", "server-a", "same-repository-id"))
        settings.putMapping(BoscaRepositoryMapping("file:///b", "server-b", "same-repository-id"))

        settings.setEnabledProfiles(listOf("server-b"))

        assertEquals(setOf("server-b"), settings.effectiveProfileIds(listOf("server-a", "server-b")))
        assertEquals("server-a", settings.mapping("file:///a")?.serverProfileId)
        assertEquals("server-b", settings.mapping("file:///b")?.serverProfileId)
    }

    private fun fakeProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    } as Project
}
