package bosca.bml.auth

import bosca.bml.auth.generated.BmlComponents
import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.render.RenderContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

class ProfileMenuComponentTest {
    @Test
    fun `account tag exports its own client lifecycle and composes the design menu`() = runBlocking {
        val id = BmlLibraryArtifacts.componentId("io.bosca:bml-auth-ui", "auth-profile-menu")
        val component = BmlComponents.all.single { it.tag == id }
        assertNotNull(component.clientModule)
        assertContains(component.deps, BmlLibraryArtifacts.componentId("io.bosca:bml-design", "profile-menu"))
        val renderer = requireNotNull(BmlComponents.renderers[id])
        val context = RenderContext()
        renderer.render(context, mapOf("defaultAppearance" to "light", "showIdentity" to true)) {
            context.writer.raw("<a role=\"menuitem\" href=\"/settings\">Settings</a>")
        }
        val html = context.writer.toString()
        assertContains(html, "data-bml-component=\"$id\"")
        assertContains(html, "data-default-appearance=\"light\"")
        assertContains(html, "data-show-identity")
        assertContains(html, "data-bml-ref=\"appearance\"")
        assertContains(html, "Dark mode")
        assertContains(html, "href=\"/settings\"")
        assertContains(html, "data-bml-ref=\"signout\"")
        val defaultContext = RenderContext()
        renderer.render(defaultContext, emptyMap()) {}
        assertFalse(defaultContext.writer.toString().contains("data-show-identity"))
        assertContains(defaultContext.writer.toString(), "data-default-appearance=\"dark\"")
    }
}
