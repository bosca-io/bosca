package bosca.messages

import bosca.bml.email.RenderedEmail
import bosca.bml.graphql.GraphQLClient
import bosca.bml.i18n.MessageCatalog
import bosca.bml.i18n.MessageSource
import bosca.bml.message.BmlMessageContext
import bosca.bml.message.BmlPushAttachment
import bosca.bml.message.BmlPushAction
import bosca.bml.message.BmlPushConversation
import bosca.bml.message.BmlPushOptions
import bosca.bml.message.BmlPushRichContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Renders every preview variant through its compiled `<message>` template and pins the
 * state-dependent copy — the forks each template was designed around.
 */
class MessageRenderTest {

    private fun render(slug: String): RenderedEmail =
        runBlocking { Samples.render(slug) } ?: error("unknown preview: $slug")

    private fun chatGraphQL(
        sequence: Long = 42,
        content: String,
    ) = object : GraphQLClient {
        override suspend fun execute(
            query: String,
            variables: JsonObject?,
            operationName: String?,
            token: String?,
        ): JsonElement {
            assertEquals("ChatNotificationMessage", operationName)
            assertEquals(CHAT_CHANNEL_ID, variables?.get("channelId")?.toString()?.trim('"'))
            assertEquals(sequence - 1, variables?.get("after")?.toString()?.toLong())
            return Json.parseToJsonElement(
                """
                {
                  "chat": {
                    "channel": {
                      "messages": [{
                        "sequence": $sequence,
                        "deleted": false,
                        "content": [$content]
                      }]
                    }
                  }
                }
                """.trimIndent(),
            )
        }
    }

    private fun reactionGraphQL(
        sequence: Long = 42,
        reactionId: String = CHAT_REACTION_ID,
        emoji: String = "👍",
    ) = object : GraphQLClient {
        override suspend fun execute(
            query: String,
            variables: JsonObject?,
            operationName: String?,
            token: String?,
        ): JsonElement {
            assertEquals("ChatNotificationReaction", operationName)
            assertEquals(CHAT_CHANNEL_ID, variables?.get("channelId")?.toString()?.trim('"'))
            assertEquals(sequence - 1, variables?.get("after")?.toString()?.toLong())
            return Json.parseToJsonElement(
                """
                {
                  "chat": {
                    "channel": {
                      "messages": [{
                        "sequence": $sequence,
                        "deleted": false,
                        "reactions": [
                          {"id": "00000000-0000-0000-0000-000000000099", "emoji": "❤️"},
                          {"id": "$reactionId", "emoji": "$emoji"}
                        ]
                      }]
                    }
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `every variant renders a full email document with absolute asset urls`() {
        for (entry in Samples.entries) {
            val renderedMessage = runBlocking { Samples.renderMessage(entry.slug) }
                ?: error("unknown preview: ${entry.slug}")
            val rendered = renderedMessage.email ?: error("${entry.slug}: email channel missing")
            assertTrue(rendered.subject.isNotBlank(), "${entry.slug}: blank subject")
            assertTrue(rendered.html.startsWith("<!doctype html>"), "${entry.slug}: not a full document")
            assertTrue(rendered.html.contains("Bosca"), "${entry.slug}: brand missing")
            assertTrue(rendered.html.contains("Manage email preferences"), "${entry.slug}: footer missing")
            assertFalse(rendered.html.contains("<script"), "${entry.slug}: email html must carry no scripts")
            assertTrue(rendered.text.isNotBlank(), "${entry.slug}: blank text alternative")
            // Emails have no origin: every asset reference must build from the context's base.
            assertTrue(rendered.html.contains("${Samples.assetsUrl}/bosca-mark.png"), "${entry.slug}: mark not absolute")
            assertTrue(
                rendered.html.contains("${Samples.assetsUrl}/fonts/geist-variable.woff2"),
                "${entry.slug}: font urls not absolute",
            )
            assertFalse(
                rendered.html.contains("url('/") || rendered.html.contains("url(/"),
                "${entry.slug}: relative asset url leaked",
            )
            if (entry.templateKey in SOCIAL_COMMUNICATION_TEMPLATES) {
                assertTrue(renderedMessage.push != null, "${entry.slug}: push channel missing")
            }
        }
    }

    @Test
    fun `asset base and preference links come from the context`() {
        val payload = kotlinx.serialization.json.Json.encodeToJsonElement(
            VerifyEmail.serializer(),
            VerifyEmail(verifyUrl = "https://example.com/verify?token=t"),
        )
        val rendered = runBlocking {
            bml.generated.BmlMessages.byKey.getValue("verify-email").renderMessage(
                BmlMessageContext(
                    recipientName = "Sarah",
                    assetsUrl = "https://messages.example.com/assets/bosca-messages/1",
                    unsubscribeUrl = "https://example.com/u/abc",
                    preferencesUrl = "https://example.com/prefs",
                    payload = payload,
                ),
            )
        }
        val email = rendered.email ?: error("email channel missing")
        assertTrue(email.html.contains("https://messages.example.com/assets/bosca-messages/1/bosca-mark.png"), email.html.take(600))
        assertTrue(email.html.contains("https://messages.example.com/assets/bosca-messages/1/fonts/geist-mono-variable.woff2"))
        assertTrue(email.html.contains("href=\"https://example.com/u/abc\""))
        assertTrue(email.html.contains("href=\"https://example.com/prefs\""))
        assertTrue(email.text.contains("Verify email address (https://example.com/verify?token=t)"))
        assertTrue(email.text.contains("Manage email preferences (https://example.com/prefs)"))
        assertTrue(email.text.contains("Unsubscribe (https://example.com/u/abc)"))
    }

    @Test
    fun `rendering without an asset base fails loudly`() {
        val entry = Samples.entries.first()
        val bare = BmlMessageContext(
            recipientName = "Sarah",
            payload = entry.context().payload,
        )
        val failure = runCatching {
            runBlocking { bml.generated.BmlMessages.byKey.getValue(entry.templateKey).renderMessage(bare) }
        }
        assertTrue(failure.isFailure, "a missing assetsUrl must not silently render relative asset urls")
    }

    @Test
    fun `unknown slug returns null`() {
        assertEquals(null, runBlocking { Samples.render("nope") })
    }

    @Test
    fun `brand title logo and colors ride the payload`() {
        val payload = kotlinx.serialization.json.Json.encodeToJsonElement(
            Welcome.serializer(),
            Welcome(
                appName = "Acme",
                logoUrl = "https://cdn.acme.example/mark.png",
                primaryColor = "#123456",
                accentColor = "#abcdef",
            ),
        )
        val rendered = runBlocking {
            bml.generated.BmlMessages.byKey.getValue("welcome").renderMessage(
                BmlMessageContext(
                    recipientName = "Sarah",
                    assetsUrl = Samples.assetsUrl,
                    payload = payload,
                ),
            ).email ?: error("email channel missing")
        }
        assertEquals("Welcome to Acme", rendered.subject)
        assertTrue(rendered.html.contains("https://cdn.acme.example/mark.png"))
        assertFalse(rendered.html.contains("bosca-mark.png"), "override must replace the default mark")
        assertTrue(rendered.html.contains(">Acme</span>"), "masthead wordmark must show the brand override")
        assertTrue(rendered.html.contains("<title>Acme · Welcome</title>"), "document title must show the brand override")
        assertTrue(rendered.html.contains("#123456"), "primary color must style headings and actions")
        assertTrue(rendered.html.contains("#abcdef"), "accent color must style highlights")
    }

    @Test
    fun `logo only branding hides the masthead title`() {
        val payload = kotlinx.serialization.json.Json.encodeToJsonElement(
            Welcome.serializer(),
            Welcome(
                appName = "Acme",
                logoUrl = "https://cdn.acme.example/mark.png",
                logoOnly = true,
            ),
        )
        val rendered = runBlocking {
            bml.generated.BmlMessages.byKey.getValue("welcome").renderMessage(
                BmlMessageContext(
                    recipientName = "Sarah",
                    assetsUrl = Samples.assetsUrl,
                    payload = payload,
                ),
            ).email ?: error("email channel missing")
        }

        assertEquals("Welcome to Acme", rendered.subject)
        assertTrue(rendered.html.contains("https://cdn.acme.example/mark.png"))
        assertFalse(rendered.html.contains(">Acme</span>"), "logo-only masthead must omit the title")
        assertTrue(rendered.html.contains("<title>Acme · Welcome</title>"), "logo-only must not change the document title")
    }

    @Test
    fun `welcome greets by name and lists the highlights`() {
        val rendered = render("01-welcome")
        assertEquals("Welcome to Bosca", rendered.subject)
        assertTrue(rendered.html.contains("Welcome to Bosca"), rendered.html.take(400))
        assertTrue(rendered.html.contains("Hi Sarah,"))
        assertTrue(rendered.html.contains("Your Bosca account is ready."))
        // Localized element text renders through the escaped text path, so the
        // authored apostrophe emits as its entity.
        assertTrue(rendered.html.contains("What&#39;s inside"))
        assertTrue(rendered.html.contains("Publish structured content"))
        assertTrue(rendered.html.contains("Automate the busywork"))
        assertTrue(rendered.html.contains("Get started"))
        assertTrue(rendered.html.contains("an account was created with this email address"))
    }

    @Test
    fun `welcome without a name drops the greeting line`() {
        val rendered = render("01b-welcome-noname")
        assertTrue(rendered.html.contains("Welcome to Bosca"), "expected the headline")
        assertFalse(rendered.html.contains("Hi Sarah"), "no greeting line when recipientName is null")
    }

    @Test
    fun `verify email carries the verification link and expiry`() {
        val rendered = render("02-verify-email")
        assertEquals("Verify your email address", rendered.subject)
        assertTrue(rendered.html.contains("Hi Sarah,"))
        assertTrue(rendered.html.contains("Verify email address"))
        assertTrue(rendered.html.contains("href=\"https://example.com/verify?token=sample-token\""))
        assertTrue(rendered.html.contains("This link expires in 24 hours."))
        assertTrue(rendered.html.contains("you can safely ignore this message"))
        assertTrue(rendered.html.contains("Or paste this link into your browser"))
    }

    @Test
    fun `reset password carries the reset link and the safe-to-ignore note`() {
        val rendered = render("03-reset-password")
        assertEquals("Reset your Bosca password", rendered.subject)
        assertTrue(rendered.html.contains("Hi Sarah,"))
        assertTrue(rendered.html.contains("Reset your password"))
        assertTrue(rendered.html.contains("Reset password"))
        assertTrue(rendered.html.contains("href=\"https://example.com/reset-password?token=sample-token\""))
        assertTrue(rendered.html.contains("This link expires in 1 hour."))
        // Escaped text path — the apostrophe emits as its entity.
        assertTrue(rendered.html.contains("your password won&#39;t change"))
    }

    @Test
    fun `security alert shows the event and its detail rows`() {
        val rendered = render("04-security-alert")
        assertEquals("Security alert for your Bosca account", rendered.subject)
        assertTrue(rendered.html.contains("Hi Sarah,"))
        assertTrue(rendered.html.contains("Security event"))
        assertTrue(rendered.html.contains("New sign-in to your account"))
        assertTrue(rendered.html.contains("Jul 17, 2026 at 2:41 PM (EDT)"))
        assertTrue(rendered.html.contains("MacBook Pro · Safari 17"))
        assertTrue(rendered.html.contains("Atlanta, GA, United States"))
        assertTrue(rendered.html.contains("203.0.113.42"))
        assertTrue(rendered.html.contains("Review account security"))
    }

    @Test
    fun `security alert without details keeps the panel tight`() {
        val rendered = render("04b-security-alert-password")
        assertTrue(rendered.html.contains("Your password was changed"))
        assertFalse(rendered.html.contains("Device"))
        assertFalse(rendered.html.contains("IP address"))
        assertTrue(rendered.html.contains("Review account security"))
    }

    @Test
    fun `form submission lists the submitted fields and the submitter`() {
        val rendered = render("05-form-submission")
        assertEquals("New contact request", rendered.subject)
        assertTrue(rendered.html.contains("New form submission"))
        assertTrue(rendered.html.contains("Hi Sarah,"))
        assertTrue(rendered.html.contains("From Jordan Avery"))
        assertTrue(rendered.html.contains("jordan@example.com"))
        assertTrue(rendered.html.contains("Jul 17, 2026 · 2:41 PM"))
        assertTrue(rendered.html.contains("Northwind Publishing"))
        assertTrue(rendered.html.contains("multi-site publishing, roles, and workflows"))
        assertTrue(rendered.html.contains("Review submission"))
        assertTrue(rendered.html.contains("a reviewer for Contact Us"))
        assertFalse(rendered.html.contains("carried no field values"))
    }

    @Test
    fun `anonymous empty submission shows the explicit empty state`() {
        val rendered = render("05b-form-submission-empty")
        assertEquals("New submission: Newsletter Signup", rendered.subject)
        assertTrue(rendered.html.contains("From Anonymous"))
        assertTrue(rendered.html.contains("This submission carried no field values."))
        assertTrue(rendered.html.contains("Review submission"))
    }

    @Test
    fun `receipt echoes the submission and the next steps`() {
        val rendered = render("06-form-receipt")
        assertEquals("Your Speaker Application submission was received", rendered.subject)
        assertTrue(rendered.html.contains("Submission received"))
        assertTrue(rendered.html.contains("Hi Sarah,"))
        assertTrue(rendered.html.contains("We review applications weekly"))
        assertTrue(rendered.html.contains("What you sent"))
        assertTrue(rendered.html.contains("Composable content at scale"))
        assertTrue(rendered.html.contains("30-minute session"))
        assertTrue(rendered.html.contains("you submitted Speaker Application"))
    }

    @Test
    fun `bare receipt skips the echo and next steps`() {
        val rendered = render("06b-form-receipt-bare")
        assertEquals("Your Newsletter Signup submission was received", rendered.subject)
        assertFalse(rendered.html.contains("What you sent"))
        assertFalse(rendered.html.contains("We review applications weekly"))
    }

    @Test
    fun `pull request activity includes the comment location task and deep link`() {
        val rendered = render("07-git-pull-request")
        assertEquals("New comment on PR #79: Send Git activity through email pipelines", rendered.subject)
        assertTrue(rendered.html.contains("New review comment"))
        assertTrue(rendered.html.contains("bosca-workspace"))
        assertTrue(rendered.html.contains("Maya Rivera"))
        assertTrue(rendered.html.contains("feature/git-email-pipelines → main"))
        assertTrue(rendered.html.contains("GitEvent.kt:48"))
        assertTrue(rendered.html.contains("preserve the WorkOps context"))
        assertTrue(rendered.html.contains("GIT-79"))
        assertTrue(rendered.html.contains("href=\"https://studio.example.com/git/pulls/sample?repo=sample&amp;number=79\""))
        assertTrue(rendered.text.contains("View pull request (https://studio.example.com/git/pulls/sample?repo=sample&number=79)"))
    }

    @Test
    fun `ref activity includes the ref movement commits task and repository link`() {
        val rendered = render("08-git-ref-update")
        assertEquals("Branch feature/git-email-pipelines updated in bosca-workspace", rendered.subject)
        assertTrue(rendered.html.contains("Branch updated"))
        assertTrue(rendered.html.contains("a4d9e8f21b → b86b58c207"))
        assertTrue(rendered.html.contains("Emit pull request activity events"))
        assertTrue(rendered.html.contains("Add Git notification email templates"))
        assertTrue(rendered.html.contains("GIT-79"))
        assertTrue(rendered.html.contains("href=\"https://studio.example.com/git/repositories/sample\""))
    }

    @Test
    fun `account link carries the confirmation and security guidance`() {
        val rendered = render("09-account-link")
        assertEquals("Confirm your Bosca account link", rendered.subject)
        assertTrue(rendered.html.contains("Confirm account link"))
        assertTrue(rendered.html.contains("leave it unconfirmed"))
        assertTrue(rendered.html.contains("href=\"https://studio.example.com/auth/link/confirm?proof=sample-proof\""))
        assertTrue(rendered.text.contains("Confirm account link (https://studio.example.com/auth/link/confirm?proof=sample-proof)"))
    }

    @Test
    fun `workops activity carries project task comment and deep link`() {
        val rendered = render("10-workops-notification")
        assertEquals("COMMS-37: Route transactional emails through pipelines", rendered.subject)
        assertTrue(rendered.html.contains("Task commented"))
        assertTrue(rendered.html.contains("Bosca Platform"))
        assertTrue(rendered.html.contains("Maya Rivera"))
        assertTrue(rendered.html.contains("security and WorkOps email paths"))
        assertTrue(rendered.html.contains("href=\"https://studio.example.com/workops/tasks/sample\""))
    }

    @Test
    fun `GitHub synchronization failure email explains the problem and recovery`() {
        val rendered = render("08b-github-sync-failed")
        assertEquals("GitHub synchronization failed in bosca-workspace", rendered.subject)
        assertTrue(rendered.html.contains("does not have repository Edit permission"))
        assertTrue(rendered.html.contains("GitHub user mappings"))
        assertTrue(rendered.html.contains("Repository permissions"))
        assertTrue(rendered.text.contains("Pull from GitHub"))
        assertTrue(rendered.text.contains("https://studio.example.com/git/settings/github"))
        assertTrue(rendered.text.contains("setting=permissions"))
        assertTrue(rendered.text.contains("setting=github"))
    }

    @Test
    fun `plain text alternative carries the key copy without markup`() {
        val rendered = render("03-reset-password")
        assertTrue(rendered.text.contains("Reset your password"), rendered.text.take(400))
        assertTrue(rendered.text.contains("Hi Sarah,"))
        assertTrue(
            rendered.text.contains("Reset password (https://example.com/reset-password?token=sample-token)"),
            "CTA destination must survive the plain-text projection:\n${rendered.text}",
        )
        assertFalse(rendered.text.contains("#preferences-sample"), "fragment-only placeholders are not useful in text")
        assertFalse(rendered.text.contains("[Bosca] Bosca"), "logo alternative must not duplicate the visible brand")
        assertFalse(rendered.text.contains("<"), "text alternative must carry no markup")
    }

    @Test
    fun `chat communication inherits producer supplied rich push context`() {
        val payload = kotlinx.serialization.json.Json.encodeToJsonElement(
            ChatMessageNotification.serializer(),
            ChatMessageNotification(
                senderName = "Ada",
                channelName = "Planning",
                channelId = CHAT_CHANNEL_ID,
                sequence = 42,
                actionUrl = "https://example.com/chat/planning?sequence=42",
            ),
        )
        val attachment = BmlPushAttachment(id = "42", url = "https://cdn.example/project.jpg")
        val pushOptions = BmlPushOptions(
            defaultAction = BmlPushAction(
                id = "open-chat",
                url = "https://example.com/chat/planning?sequence=42",
            ),
            threadId = "chat-planning",
            richContent = BmlPushRichContent(
                attachments = listOf(attachment),
                conversation = BmlPushConversation(
                    id = "planning",
                    title = "Planning",
                    messageId = "42",
                    senderId = "ada",
                    senderName = "Ada",
                    body = "Project update",
                    sentAtEpochMilliseconds = 1_765_000_000_000,
                    groupConversation = true,
                ),
            ),
        )
        val rendered = runBlocking {
            bml.generated.BmlMessages.byKey.getValue("chat-message").renderMessage(
                BmlMessageContext(
                    recipientName = "Sarah",
                    locale = "es",
                    assetsUrl = Samples.assetsUrl,
                    payload = payload,
                    gql = chatGraphQL(content = """{"type":"TEXT","content":"  Project update  "}"""),
                    messages = MessageSource.of(
                        defaultLocale = Locale.forLanguageTag("en"),
                        catalogs = mapOf(
                            "es" to MessageCatalog(
                                messages = mapOf(
                                    "chat-message.push.title" to "{senderName} en {channelName}",
                                    "chat-message.push.action.open" to "Abrir chat",
                                ),
                            ),
                        ),
                    ),
                    pushOptions = pushOptions,
                ),
            )
        }

        assertEquals("Ada en Planning", rendered.push?.title)
        assertEquals("Project update", rendered.push?.body, "user-authored chat text must not be translated")
        assertEquals("Abrir chat", rendered.push?.options?.defaultAction?.label)
        assertEquals(
            "https://example.com/chat/planning?sequence=42",
            rendered.push?.options?.defaultAction?.url,
        )
        assertEquals(
            "https://cdn.example/project.jpg",
            rendered.push?.options?.richContent?.attachments?.single()?.url,
        )
        assertEquals("42", rendered.push?.options?.richContent?.conversation?.messageId)
    }

    @Test
    fun `chat attachment-only copy is localized by the message template`() {
        val payload = kotlinx.serialization.json.Json.encodeToJsonElement(
            ChatMessageNotification.serializer(),
            ChatMessageNotification(
                senderName = "Ada",
                channelName = "Planning",
                channelId = CHAT_CHANNEL_ID,
                sequence = 42,
                actionUrl = "https://example.com/chat/planning",
            ),
        )
        val rendered = runBlocking {
            bml.generated.BmlMessages.byKey.getValue("chat-message").renderMessage(
                BmlMessageContext(
                    recipientName = "Sarah",
                    locale = "es",
                    assetsUrl = Samples.assetsUrl,
                    payload = payload,
                    gql = chatGraphQL(content = """{"type":"IMAGE","content":"image-id"}"""),
                    pushOptions = BmlPushOptions(
                        defaultAction = BmlPushAction("open-chat", url = "https://example.com/chat/planning"),
                    ),
                    messages = MessageSource.of(
                        defaultLocale = Locale.forLanguageTag("en"),
                        catalogs = mapOf(
                            "es" to MessageCatalog(
                                messages = mapOf(
                                    "chat-message.attachment-preview" to "Envió un archivo adjunto",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertEquals("Envió un archivo adjunto", rendered.push?.body)
        assertTrue(rendered.email?.text?.contains("Envió un archivo adjunto") == true)
    }

    @Test
    fun `direct chat copy names the sender without exposing an internal channel name`() {
        val payload = kotlinx.serialization.json.Json.encodeToJsonElement(
            ChatMessageNotification.serializer(),
            ChatMessageNotification(
                senderName = "Ada",
                channelName = "DM",
                direct = true,
                channelId = CHAT_CHANNEL_ID,
                sequence = 42,
                actionUrl = "https://example.com/chat/direct?sequence=42",
            ),
        )
        val rendered = runBlocking {
            bml.generated.BmlMessages.byKey.getValue("chat-message").renderMessage(
                BmlMessageContext(
                    recipientName = "Sarah",
                    assetsUrl = Samples.assetsUrl,
                    payload = payload,
                    gql = chatGraphQL(content = """{"type":"TEXT","content":"Can you review this?"}"""),
                    pushOptions = BmlPushOptions(
                        defaultAction = BmlPushAction(
                            "open-chat",
                            url = "https://example.com/chat/direct?sequence=42",
                        ),
                    ),
                ),
            )
        }

        assertEquals("Ada sent you a message", rendered.email?.subject)
        assertEquals("Ada", rendered.push?.title)
        assertTrue(rendered.email?.text?.contains("New message from Ada") == true)
        assertTrue(rendered.email?.text?.contains("Ada sent you a direct message while you were away.") == true)
        assertFalse(rendered.email?.text?.contains("DM") == true)
    }

    @Test
    fun `chat reaction fetches its emoji without carrying it in the payload`() {
        val notification = ChatReactionNotification(
            reactorName = "Ada",
            channelId = CHAT_CHANNEL_ID,
            sequence = 42,
            reactionId = CHAT_REACTION_ID,
            actionUrl = "https://example.com/chat/planning?sequence=42",
        )
        val payload = Json.encodeToJsonElement(ChatReactionNotification.serializer(), notification)
        assertNull(payload.jsonObject["emoji"])

        val rendered = runBlocking {
            bml.generated.BmlMessages.byKey.getValue("chat-reaction").renderMessage(
                BmlMessageContext(
                    recipientName = "Sarah",
                    payload = payload,
                    gql = reactionGraphQL(),
                    pushOptions = BmlPushOptions(
                        defaultAction = BmlPushAction(
                            id = "open-chat",
                            url = notification.actionUrl,
                        ),
                    ),
                ),
            )
        }

        assertNull(rendered.email)
        assertEquals("Ada reacted to your message", rendered.push?.title)
        assertEquals("👍", rendered.push?.body)
        assertEquals("Open chat", rendered.push?.options?.defaultAction?.label)
        assertEquals(notification.actionUrl, rendered.push?.options?.defaultAction?.url)
    }

    @Test
    fun `prayer reaction renders prayed and liked push copy`() {
        for ((reaction, expectedBody) in listOf(
            "PRAYED" to "Ada prayed for your prayer",
            "LIKED" to "Ada liked your prayer",
        )) {
            val notification = PrayerReactionNotification(
                actorName = "Ada",
                reaction = reaction,
                actionUrl = "https://app.example.com/prayers/prayer-id",
            )
            val rendered = runBlocking {
                bml.generated.BmlMessages.byKey.getValue("prayer-reaction").renderMessage(
                    BmlMessageContext(
                        payload = Json.encodeToJsonElement(PrayerReactionNotification.serializer(), notification),
                        pushOptions = BmlPushOptions(
                            defaultAction = BmlPushAction("open-prayer", url = notification.actionUrl),
                        ),
                    ),
                )
            }

            assertNull(rendered.email)
            assertEquals("Prayer activity", rendered.push?.title)
            assertEquals(expectedBody, rendered.push?.body)
            assertEquals("Open prayer", rendered.push?.options?.defaultAction?.label)
            assertEquals(notification.actionUrl, rendered.push?.options?.defaultAction?.url)
        }
    }

    @Test
    fun `prayer comment distinguishes comments from replies`() {
        for ((reply, expectedTitle, expectedBody) in listOf(
            Triple(false, "New prayer comment", "Ada commented on your prayer"),
            Triple(true, "New reply", "Ada replied to your comment"),
        )) {
            val notification = PrayerCommentNotification(
                actorName = "Ada",
                reply = reply,
                actionUrl = "https://app.example.com/prayers/prayer-id?commentId=42",
            )
            val rendered = runBlocking {
                bml.generated.BmlMessages.byKey.getValue("prayer-comment").renderMessage(
                    BmlMessageContext(
                        payload = Json.encodeToJsonElement(PrayerCommentNotification.serializer(), notification),
                        pushOptions = BmlPushOptions(
                            defaultAction = BmlPushAction("open-prayer", url = notification.actionUrl),
                        ),
                    ),
                )
            }

            assertNull(rendered.email)
            assertEquals(expectedTitle, rendered.push?.title)
            assertEquals(expectedBody, rendered.push?.body)
            assertEquals("Open prayer", rendered.push?.options?.defaultAction?.label)
            assertEquals(notification.actionUrl, rendered.push?.options?.defaultAction?.url)
        }
    }

    companion object {
        private const val CHAT_CHANNEL_ID = "00000000-0000-0000-0000-000000000042"
        private const val CHAT_REACTION_ID = "00000000-0000-0000-0000-000000000043"
        private val SOCIAL_COMMUNICATION_TEMPLATES = setOf(
            "chat-message",
            "channel-invitation",
            "channel-joined",
            "relationship-request",
            "relationship-added",
        )
    }
}
