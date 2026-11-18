package bosca.profiles.web

import bosca.bml.render.BmlComponentRenderer
import bosca.bml.render.RenderContext
import bosca.profiles.web.graphql.ProfileVisibility
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Renders the actual compiled tags for the isolated client lifecycle suite. */
class ComponentLifecycleTest {
    @Test
    fun `interactive tags own their client modules and render standalone fixtures`() = runBlocking {
        val fixtures = buildJsonObject {
            suspend fun render(name: String, component: BmlComponentRenderer, props: Map<String, Any?>) {
                val context = RenderContext()
                component.render(context, props) {}
                put(name, context.writer.toString())
            }
            val field = ProfileAttributeFieldRow(key = "value", label = "Value", value = "Original")
            val attribute = ProfileAttributeRow("attribute", "example", "Example", "", "Original", "user-input", 100, 100, ProfileVisibility.PUBLIC, "", false, false, field)
            val profile = ProfileEditorModel("profile", "Jamie", "jamie", ProfileVisibility.PUBLIC, true,
                "2026-08-15T23:37:36.777736Z", "2026-08-15T23:37:36.777736Z", listOf(attribute),
                listOf(ProfileAttributeTypeRow("example", "Example", "", ProfileVisibility.PUBLIC, false, field)))
            render("details", bml.generated.ProfileDetailsComponent, mapOf("initial" to profile))
            val other = ProfileEditorModel("other", "Other", "other", ProfileVisibility.PUBLIC, false, "today", "today", emptyList(), emptyList())
            render("otherDetails", bml.generated.ProfileDetailsComponent, mapOf("initial" to other))
            render("attributes", bml.generated.ProfileAttributesComponent, mapOf("initial" to profile))
            val dispatcher = bml.generated.BmlIslands.componentDispatchers.getValue("profile-attributes.profile")
            val selected = dispatcher.dispatch(RenderContext(), "chooseAttributeType", Json.encodeToString(ProfileEditorModel.serializer(), profile),
                JsonArray(listOf(JsonPrimitive("example"))), "profile-attributes.profile:profile")
            put("selectedAttribute", requireNotNull(selected.html))
            render("date", bml.generated.LocalDateTimeComponent, mapOf("value" to "2026-08-15T23:37:36.777736Z"))
            render("visibility", bml.generated.ProfileVisibilityMenuComponent, mapOf("visibility" to ProfileVisibility.PUBLIC))
            render("actions", bml.generated.ProfileAttributeActionsComponent, emptyMap())
            render("toast", bml.generated.ToastMessageComponent, mapOf("message" to "Old notice", "failed" to false, "id" to 1L, "preserveKey" to "notice"))
            render("signOut", bml.generated.SignOutLinkComponent, mapOf("label" to "Return to sign in", "href" to "/login"))
        }
        for (tag in listOf("profile-details", "profile-attributes", "profile-visibility-menu", "profile-attribute-actions", "local-date-time", "toast-message", "sign-out-link")) {
            assertNotNull(bml.generated.BmlComponents.all.single { it.tag == tag }.clientModule, "$tag must initialize itself")
        }
        assertTrue(fixtures.getValue("details").jsonPrimitive.content.contains("data-bml-component=\"profile-details\""))
        val directory = File(System.getProperty("profilesFixtures")).apply { mkdirs() }
        directory.resolve("components.json").writeText(fixtures.toString())
    }
}
