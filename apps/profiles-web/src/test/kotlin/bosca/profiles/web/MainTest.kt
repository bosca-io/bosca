package bosca.profiles.web

import bosca.bml.render.RenderContext
import bosca.profiles.web.graphql.ProfileDetailsData
import bosca.profiles.web.graphql.ProfileVisibility
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

class MainTest {
    @Test
    fun `port defaults to 9095 and honors the first argument`() {
        assertEquals(9095, resolvePort(emptyArray()))
        assertEquals(9095, resolvePort(arrayOf("not-a-port")))
        assertEquals(8081, resolvePort(arrayOf("8081")))
    }

    @Test
    fun `graphql endpoint defaults to the local API`() {
        assertEquals("http://localhost:8080/graphql", graphqlEndpoint())
    }

    @Test
    fun `profiles public URL builds absolute allow-listed OAuth return targets`() {
        val publicUrl = profilesWebPublicUrl(
            mapOf("PROFILES_WEB_PUBLIC_URL" to " https://profiles.example/ "),
        )

        assertEquals("https://profiles.example", publicUrl)
        assertEquals(
            "/oauth2/google/login?redirect=https%3A%2F%2Fprofiles.example%2Flogin%3Fredirect%3D%252Fsecurity%253Ftab%253Dlogins&originator=profiles-web",
            profilesOAuthUrl("google", "login", "/security?tab=logins", publicUrl),
        )
        assertEquals(
            "/oauth2/google/login?redirect=https%3A%2F%2Fprofiles.example%2Flogin%3Fredirect%3D%252F&originator=profiles-web",
            profilesOAuthUrl("google", "login", "//unexpected.example", publicUrl),
        )
        assertEquals("https://profiles.example/", profilesWebRedirect("//unexpected.example", publicUrl))
    }

    @Test
    fun `profiles public URL rejects paths and unsupported schemes`() {
        assertFailsWith<IllegalArgumentException> {
            profilesWebPublicUrl(mapOf("PROFILES_WEB_PUBLIC_URL" to "https://profiles.example/account"))
        }
        assertFailsWith<IllegalArgumentException> {
            profilesWebPublicUrl(mapOf("PROFILES_WEB_PUBLIC_URL" to "javascript:alert(1)"))
        }
    }

    @Test
    fun `profiles cookie domain is normalized for shared browser auth`() {
        assertNull(profilesWebCookieDomain(emptyMap()))
        assertEquals(
            "example.com",
            profilesWebCookieDomain(mapOf("PROFILES_WEB_COOKIE_DOMAIN" to " .Example.Com ")),
        )
    }

    @Test
    fun `profiles cookie domain rejects URLs ports and paths`() {
        listOf("https://example.com", "example.com:443", "example.com/profiles").forEach { domain ->
            assertFailsWith<IllegalArgumentException> {
                profilesWebCookieDomain(mapOf("PROFILES_WEB_COOKIE_DOMAIN" to domain))
            }
        }
    }

    @Test
    fun `account attribute list excludes system-owned values`() {
        fun attribute(
            id: String,
            visibility: ProfileVisibility,
            typeVisibility: ProfileVisibility,
        ) = ProfileDetailsData.Profiles.Current.Attributes(
            id = id,
            typeId = "type-$id",
            source = "test",
            priority = 100,
            attributes = JsonObject(emptyMap()),
            confidence = 100,
            visibility = visibility,
            expires = null,
            verified = false,
            verificationSource = null,
            type = ProfileDetailsData.Profiles.Current.Attributes.Type(
                id = "type-$id",
                name = id,
                description = "",
                visibility = typeVisibility,
                protected = false,
                formSchema = null,
            ),
        )

        val rows = listOf(
            attribute("visible", ProfileVisibility.USER, ProfileVisibility.PUBLIC),
            attribute("system-value", ProfileVisibility.SYSTEM, ProfileVisibility.PUBLIC),
            attribute("system-type", ProfileVisibility.USER, ProfileVisibility.SYSTEM),
        ).toAccountAttributeRows()

        assertEquals(listOf("visible"), rows.map { it.id })
    }

    @Test
    fun `client directory resolves only when it exists`() {
        assertNull(resolveClientDir("/definitely/not/a/dir", development = true))
        val temporary = File(System.getProperty("java.io.tmpdir"))
        assertEquals(temporary, resolveClientDir(temporary.absolutePath, development = false))
    }

    @Test
    fun `branding has Bosca defaults`() {
        assertEquals(
            Branding("Bosca", "", "© Bosca", "#00dc82", "#06b6d4"),
            branding(emptyMap()),
        )
    }

    @Test
    fun `branding reads deployment overrides`() {
        val footer = File.createTempFile("profiles-footer-", ".html").apply { writeText("<a href=\"/privacy\">Privacy</a>") }
        try {
            assertEquals(
                Branding("Acme", "https://cdn.example/logo.svg", footer.readText(), "#123456", "#abc"),
                branding(
                    mapOf(
                        "BRAND_NAME" to " Acme ",
                        "BRAND_LOGO_URL" to "https://cdn.example/logo.svg",
                        "BRAND_FOOTER_HTML_FILE" to footer.absolutePath,
                        "BRAND_PRIMARY_COLOR" to "#123456",
                        "BRAND_ACCENT_COLOR" to "#abc",
                    ),
                ),
            )
        } finally {
            footer.delete()
        }
    }

    @Test
    fun `branding rejects stylesheet injection`() {
        val configured = branding(
            mapOf(
                "BRAND_PRIMARY_COLOR" to "#123456; } body { display: none",
                "BRAND_ACCENT_COLOR" to "rebeccapurple",
            ),
        )
        assertEquals(DEFAULT_PRIMARY_COLOR, configured.primaryColor)
        assertEquals(DEFAULT_ACCENT_COLOR, configured.accentColor)
    }

    @Test
    fun `configured footer must be readable`() {
        assertFailsWith<java.io.FileNotFoundException> {
            branding(mapOf("BRAND_FOOTER_HTML_FILE" to "/definitely/not/a/footer.html"))
        }
    }

    @Test
    fun `theme css contains validated deployment colors`() {
        val css = profilesCss(Branding("Acme", "", "© Acme", "#112233", "#445566"))
        assertTrue("--primary: #112233" in css)
        assertTrue("--accent: #445566" in css)
        assertTrue("--brand-1: #112233" in css)
        assertTrue("--brand-2: #445566" in css)
        assertTrue(".attribute-form" in css)
        assertTrue("button.toggle:before{position:absolute" in css)
        assertTrue(".toast-container{position:fixed" in css)
        assertTrue(".visibility-popover" in css)
        assertTrue(".attribute-menu-popover" in css)
        assertTrue(".profile-modal::backdrop" in css)
        assertTrue("background-position:right 13px center" in css)
        assertTrue(".profile-details-card{z-index:2;overflow:visible" in css)
        assertTrue(".attribute-dirty-popover" in css)
        assertTrue(".profile-modal.attribute-modal,.attribute-modal .modal-body{overflow:visible" in css)
        assertTrue(".switch-input:checked+.switch-control" in css)
        assertTrue(".overview-item{display:grid" in css)
        assertTrue(".bosca-form" !in css)
    }

    @Test
    fun `minified global client preserves shared browser auth contracts and behavior markers`() {
        val javascript = profilesJs(mapOf("PROFILES_WEB_COOKIE_DOMAIN" to "example.com"))
        assertTrue(
            "globalThis.profilesWebConfig = {\"cookieDomain\":\"example.com\"};" in javascript,
            "the runtime cookie domain must be available before the bundled auth client starts",
        )
        assertTrue("profilesWebConfig" in javascript)
        assertTrue("session restore failed" in javascript)
        assertTrue("handleRedirectResult" in javascript, "cross-domain OAuth callbacks must exchange their one-time token")
        assertTrue("OAuth exchange failed" in javascript)
        assertTrue("location.replace" in javascript, "a completed OAuth exchange must resume the requested account route")
        assertTrue("profilesReturnPath" in javascript, "OAuth and password login must share safe return-path handling")
        assertTrue("process.env" !in javascript, "the browser bundle must not depend on the Node.js process global")
        assertTrue("/api/v1/security/auth-client?key=" !in javascript, "cookie selection must not require metadata I/O")
        assertTrue("bmlConfig" in javascript)
        assertTrue("authCookiePrefix" in javascript)
        assertTrue("authReady" !in javascript, "auth initialization must not require an asynchronous factory")
        assertTrue("window.auth=" in javascript, "the shared auth client must be published for BML page modules")
        assertTrue("profiles-toast-container" in javascript, "mutation feedback must use one global toast surface")
        assertTrue("toast-close" in javascript, "toasts must be dismissible")
        assertTrue("showModal" in javascript, "adding an attribute must use a modal")
        assertTrue("data-remove-attribute" in javascript, "removal must be disabled while edits are unsaved")
        assertTrue("data-open-remove-attribute-dialog" in javascript, "removal must require confirmation")
        assertTrue("data-visibility-label" in javascript, "the visibility indicator must track selection changes")
        assertTrue("Save or discard your changes before adding an attribute." in javascript)
        assertTrue("data-sign-out-before-navigation" in javascript)
        assertTrue(".destroy()" in javascript, "revoked sessions must clear local tokens before returning to login")
        assertTrue("Intl.DateTimeFormat" in javascript, "server timestamps must be localized in the browser")
        assertTrue("recentUserActions" !in javascript, "local identifiers should be mangled in production")
        assertTrue("BoscaForm" !in javascript)
        assertTrue("createApp" !in javascript)
    }

    @Test
    fun `security model selects one tab at a time`() {
        fun model(tab: String) = SecurityModel(
            principalId = "principal",
            verified = true,
            created = "today",
            lastLogin = "today",
            credentials = emptyList(),
            thirdPartyProviders = emptyList(),
            passkeys = emptyList(),
            logins = emptyList(),
            tab = tab,
        )

        assertTrue(model("password").passwordTab)
        assertTrue(model("connected").connectedTab)
        assertTrue(model("passkeys").passkeysTab)
        assertTrue(model("logins").loginsTab)
    }

    @Test
    fun `security model counts only active sign-ins`() {
        val model = SecurityModel(
            principalId = "principal",
            verified = true,
            created = "today",
            lastLogin = "today",
            credentials = emptyList(),
            thirdPartyProviders = emptyList(),
            passkeys = emptyList(),
            logins = listOf(
                LoginRow(1, "password", "today", "", true),
                LoginRow(2, "password", "yesterday", "today", false),
            ),
            tab = "logins",
        )

        assertEquals(1, model.activeLoginCount)
    }

    @Test
    fun `security component isolates persisted state by tab`() = runBlocking {
        val model = SecurityModel(
            principalId = "principal",
            verified = true,
            created = "today",
            lastLogin = "today",
            credentials = emptyList(),
            thirdPartyProviders = emptyList(),
            passkeys = emptyList(),
            logins = emptyList(),
            tab = "logins",
        )
        val context = RenderContext()

        bml.generated.AccountSecurityComponent.render(context, mapOf("initial" to model)) {}

        val html = context.writer.toString()
        assertTrue("data-bml-state-key=\"account-security.model:logins\"" in html, html)
        assertTrue("data-bml-state-key=\"model\"" !in html, html)
    }

    @Test
    fun `friend request picker owns explicit open and selected state`() {
        val model = RelationshipsModel("profile", emptyList(), emptyList(), emptyList())

        model.openFriendRequest()
        assertTrue(model.friendRequestOpen)
        assertFalse(model.canSendFriendRequest)

        model.selectFriend("friend", "Ada Lovelace")
        assertTrue(model.canSendFriendRequest)
        assertEquals("Ada Lovelace", model.selectedFriendName)

        model.closeFriendRequest()
        assertFalse(model.friendRequestOpen)
    }

    @Test
    fun `friend request picker renders declarative search and send actions`() = runBlocking {
        val model = RelationshipsModel("profile", emptyList(), emptyList(), emptyList())
        model.openFriendRequest()
        val context = RenderContext()

        bml.generated.ProfileRelationshipsProfileRelationshipsIsland.renderInner(context, model, "relationships")

        val html = context.writer.toString()
        assertTrue("data-bml-method=\"searchFriends\"" in html, html)
        assertTrue("data-bml-debounce-ms=\"300\"" in html, html)
        assertTrue("data-bml-coalesce" in html, html)
        assertTrue("data-bml-preserve=\"friend-request-search\"" in html, html)
        assertTrue("data-bml-method=\"sendFriendRequest\"" in html, html)
    }

    @Test
    fun `friend search uses the public profile index and requests avatar attributes`() {
        val document = bosca.profiles.web.graphql.SearchFriendProfiles.document

        assertTrue("Profile Search Index" in document)
        assertTrue("Admin Search Index" !in document)
        assertTrue("attributes { typeId attributes }" in document)
    }

    @Test
    fun `profile search result derives compact initials`() {
        assertEquals("AL", ProfileSearchRow("id", "Ada Lovelace", "ada", "").initials)
        assertEquals("P", ProfileSearchRow("id", "Prince", "prince", "").initials)
        assertEquals("?", ProfileSearchRow("id", " ", "", "").initials)
    }

    @Test
    fun `profile avatar renders an available image`() = runBlocking {
        val context = RenderContext()

        bml.generated.ProfileAvatarComponent.render(
            context,
            mapOf("name" to "Ada Lovelace", "initials" to "AL", "src" to "https://cdn.example/ada.jpg"),
        ) {}

        val html = context.writer.toString()
        assertTrue("<img" in html, html)
        assertTrue("https://cdn.example/ada.jpg" in html, html)
        assertTrue("AL" !in html, html)
    }

    @Test
    fun `layout renders custom logo footer and account navigation`() = runBlocking {
        val context = RenderContext()
        val footer = "<a href=\"/privacy\">Privacy</a>"
        bml.generated.SiteLayoutComponent.render(
            context,
            mapOf(
                "title" to "Account",
                "brand" to "Acme",
                "logo" to "https://cdn.example/logo.svg?size=2&mode=fit",
                "footer" to footer,
                "signedIn" to true,
            ),
        ) {}
        val html = context.writer.toString()
        assertTrue("logo.svg?size=2&amp;mode=fit" in html, html)
        assertTrue("/relationships" in html, html)
        assertTrue("data-theme=\"dark\"" in html, html)
        assertTrue(footer in html, html)
    }

    @Test
    fun `default Bosca layout renders the brand mark`() = runBlocking {
        val context = RenderContext()
        bml.generated.SiteLayoutComponent.render(
            context,
            mapOf(
                "title" to "Account overview",
                "brand" to "Bosca",
                "logo" to "",
                "footer" to "© Bosca",
                "signedIn" to true,
            ),
        ) {}
        val html = context.writer.toString()
        assertTrue("class=\"brand-mark\"" in html, html)
        assertTrue("class=\"active\"" in html, html)
    }

    @Test
    fun `visibility field uses the platform labels`() = runBlocking {
        val context = RenderContext()
        bml.generated.ProfileVisibilityFieldComponent.render(
            context,
            mapOf("visibility" to bosca.profiles.web.graphql.ProfileVisibility.USER),
        ) {}
        val html = context.writer.toString()
        assertTrue("aria-label=\"Change visibility\"" in html, html)
        assertTrue("class=\"visibility-current\" data-visibility-label" in html, html)
        assertTrue(">User</option>" in html, html)
        assertTrue(">Friends</option>" in html, html)
        assertTrue(">Friends of Friends</option>" in html, html)
        assertTrue(">Public</option>" in html, html)
        assertTrue("value=\"SYSTEM\"" !in html, html)
    }

    @Test
    fun `system visibility is hidden and preserved`() = runBlocking {
        val context = RenderContext()
        bml.generated.ProfileVisibilityFieldComponent.render(
            context,
            mapOf("visibility" to bosca.profiles.web.graphql.ProfileVisibility.SYSTEM),
        ) {}
        val html = context.writer.toString()
        assertTrue("<input name=\"visibility\" type=\"hidden\" value=\"SYSTEM\">" in html, html)
        assertTrue("<select" !in html, html)
        assertTrue(">System<" !in html, html)
    }

    @Test
    fun `new attributes default to public visibility`() = runBlocking {
        val context = RenderContext()
        bml.generated.ProfileAddAttributeFormComponent.render(
            context,
            mapOf(
                "type" to ProfileAttributeTypeRow(
                    id = "example",
                    name = "Example",
                    description = "",
                    visibility = bosca.profiles.web.graphql.ProfileVisibility.USER,
                    protected = false,
                    field = ProfileAttributeFieldRow(key = "value", label = "Value"),
                ),
            ),
        ) {}
        val html = context.writer.toString()
        assertTrue("<option value=\"PUBLIC\" selected>Public</option>" in html, html)
    }

    @Test
    fun `profile details do not expose the internal slug`() = runBlocking {
        val context = RenderContext()
        bml.generated.ProfileDetailsComponent.render(
            context,
            mapOf(
                "initial" to ProfileEditorModel(
                    id = "profile",
                    name = "Example",
                    slug = "internal-slug",
                    visibility = bosca.profiles.web.graphql.ProfileVisibility.PUBLIC,
                    searchable = true,
                    created = "today",
                    modified = "today",
                    attributes = emptyList(),
                    attributeTypes = emptyList(),
                ),
            ),
        ) {}
        val html = context.writer.toString()
        assertTrue("Profile address" !in html, html)
        assertTrue("name=\"slug\"" !in html, html)
        assertTrue("class=\"profile-form-footer full\"" in html, html)
        assertTrue("class=\"actions full\"" !in html, html)
        assertTrue("data-profile-details-form" in html, html)
        assertTrue("data-profile-save disabled" in html, html)
        assertTrue("role=\"switch\"" in html, html)
        assertTrue("class=\"switch-control\"" in html, html)
        assertTrue(">Save profile</button>" !in html, html)
        assertTrue(">Save</button>" in html, html)
    }

    @Test
    fun `attribute editor fills the card and keeps removal in a compact menu`() = runBlocking {
        val context = RenderContext()
        bml.generated.ProfileAttributeFormComponent.render(
            context,
            mapOf(
                "attribute" to ProfileAttributeRow(
                    id = "attribute",
                    typeId = "example",
                    typeName = "Example",
                    description = "",
                    value = "value",
                    source = "user-input",
                    priority = 100,
                    confidence = 100,
                    visibility = bosca.profiles.web.graphql.ProfileVisibility.PUBLIC,
                    expires = "",
                    protected = false,
                    verified = false,
                    field = ProfileAttributeFieldRow(key = "value", label = "Value", value = "value"),
                ),
            ),
        ) {}
        val html = context.writer.toString()
        assertTrue("data-attribute-editor" in html, html)
        assertTrue("aria-label=\"Attribute actions\"" in html, html)
        assertTrue("class=\"attribute-menu-popover\"" in html, html)
        assertTrue(">Save changes</button>" !in html, html)
        assertTrue(">Save</button>" !in html, html)
    }

    @Test
    fun `profile attributes use one save action and a modal add action`() = runBlocking {
        val context = RenderContext()
        val attribute = ProfileAttributeRow(
            id = "attribute",
            typeId = "example",
            typeName = "Example",
            description = "",
            value = "value",
            source = "user-input",
            priority = 100,
            confidence = 100,
            visibility = bosca.profiles.web.graphql.ProfileVisibility.PUBLIC,
            expires = "",
            protected = false,
            verified = false,
            field = ProfileAttributeFieldRow(key = "value", label = "Value", value = "value"),
        )
        bml.generated.ProfileAttributesComponent.render(
            context,
            mapOf(
                "initial" to ProfileEditorModel(
                    id = "profile",
                    name = "Example",
                    slug = "",
                    visibility = bosca.profiles.web.graphql.ProfileVisibility.PUBLIC,
                    searchable = true,
                    created = "today",
                    modified = "today",
                    attributes = listOf(attribute),
                    attributeTypes = listOf(
                        ProfileAttributeTypeRow(
                            id = "example",
                            name = "Example",
                            description = "",
                            visibility = bosca.profiles.web.graphql.ProfileVisibility.PUBLIC,
                            protected = false,
                            field = attribute.field,
                        ),
                        ProfileAttributeTypeRow(
                            id = "protected",
                            name = "Protected",
                            description = "",
                            visibility = bosca.profiles.web.graphql.ProfileVisibility.PUBLIC,
                            protected = true,
                            field = attribute.field,
                        ),
                        ProfileAttributeTypeRow(
                            id = "system",
                            name = "System",
                            description = "",
                            visibility = bosca.profiles.web.graphql.ProfileVisibility.SYSTEM,
                            protected = false,
                            field = attribute.field,
                        ),
                    ),
                ),
            ),
        ) {}
        val html = context.writer.toString()
        assertTrue("data-open-attribute-modal" in html, html)
        assertTrue("<dialog class=\"profile-modal attribute-modal\"" in html, html)
        assertTrue("data-profile-attributes-form" in html, html)
        assertTrue("data-remove-attribute" in html, html)
        assertTrue("data-remove-attribute-dialog" in html, html)
        assertTrue("data-confirm-remove-attribute" in html, html)
        assertTrue("Remove attribute?" in html, html)
        assertTrue(">Save</button>" in html, html)
        assertTrue("attribute-dirty-marker" in html, html)
        assertTrue("This attribute has changes that need to be saved." in html, html)
        assertTrue("Changes apply together." !in html, html)
        assertTrue("<option value=\"protected\"" !in html, html)
        assertTrue("<option value=\"system\"" !in html, html)
        assertTrue("count-pill" !in html, html)
        assertTrue("add-panel" !in html, html)
    }

    @Test
    fun `toast payload carries tone identity and optional action`() = runBlocking {
        val context = RenderContext()
        bml.generated.ToastMessageComponent.render(
            context,
            mapOf(
                "message" to "Sign in again.",
                "failed" to true,
                "id" to 7L,
                "actionHref" to "/login",
                "actionLabel" to "Sign in again",
                "actionSignOut" to true,
            ),
        ) {}
        val html = context.writer.toString()
        assertTrue("data-toast-tone=\"error\"" in html, html)
        assertTrue("data-toast-id=\"7\"" in html, html)
        assertTrue("data-toast-action-href=\"/login\"" in html, html)
        assertTrue("data-toast-action-sign-out" in html, html)
        assertTrue(" hidden" in html, html)
    }

    @Test
    fun `error page signs out before returning to login`() = runBlocking {
        val context = RenderContext()
        bml.generated.Pages500Page.render(context)
        val html = context.writer.toString()
        assertTrue("href=\"/login\" data-sign-out-before-navigation" in html, html)
        assertTrue("Return to sign in" in html, html)
    }

    @Test
    fun `local date time renders semantic source value`() = runBlocking {
        val context = RenderContext()
        bml.generated.LocalDateTimeComponent.render(
            context,
            mapOf("value" to "2026-08-15T23:37:36.777736Z"),
        ) {}
        val html = context.writer.toString()
        assertTrue("datetime=\"2026-08-15T23:37:36.777736Z\"" in html, html)
        assertTrue("data-local-date-time" in html, html)
    }
}
