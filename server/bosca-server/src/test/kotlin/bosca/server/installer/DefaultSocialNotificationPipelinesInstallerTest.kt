package bosca.server.installer

import bosca.chat.events.ChatChannelInvitationSentEvent
import bosca.chat.events.ChatChannelJoinedEvent
import bosca.chat.events.ChatMessageReactionAddedEvent
import bosca.chat.events.ChatMessageSentEvent
import bosca.chat.pipeline.ChatChannelInvitationNotificationNode
import bosca.chat.pipeline.ChatChannelJoinedNotificationNode
import bosca.chat.pipeline.ChatMessageNotificationNode
import bosca.chat.pipeline.ChatMessageReactionNotificationNode
import bosca.collaboration.events.ChatMentionEvent
import bosca.collaboration.pipeline.ChatMentionNotificationNode
import bosca.community.events.PrayerCommentAddedEvent
import bosca.community.events.PrayerReactionAddedEvent
import bosca.community.pipeline.PrayerCommentNotificationNode
import bosca.community.pipeline.PrayerReactionNotificationNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.service.PipelineService
import bosca.profile.relationship.events.ProfileRelationshipRequestApproved
import bosca.profile.relationship.events.ProfileRelationshipRequested
import bosca.profile.relationship.pipeline.ProfileRelationshipAddedNotificationNode
import bosca.profile.relationship.pipeline.ProfileRelationshipRequestedNotificationNode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultSocialNotificationPipelinesInstallerTest {

    private fun installer(
        existing: List<String> = emptyList(),
    ): Pair<DefaultSocialNotificationPipelinesInstaller, MutableList<Pipeline>> {
        val captured = mutableListOf<Pipeline>()
        val pipelines = mockk<PipelineService>()
        coEvery { pipelines.getAll() } returns existing.map { name ->
            mockk<Pipeline> { every { this@mockk.name } returns name }
        }
        coEvery { pipelines.graphAsJsonElement(any()) } answers {
            firstArg<Pipeline>().also(captured::add)
            JsonObject(emptyMap())
        }
        coEvery {
            pipelines.save(
                id = any(), name = any(), description = any(), acceptedInputType = any(),
                triggered = any(), version = any(), graph = any(), tags = any(), key = any(),
                api = any(), public = any(), schedule = any(), maxConcurrentRuns = any(),
                maxRunsPerMinute = any(),
            )
        } returns mockk(relaxed = true)
        return DefaultSocialNotificationPipelinesInstaller(pipelines) to captured
    }

    @Test
    fun `seeds one editable triggered pipeline for each social notification event`() = runTest {
        val (installer, captured) = installer()

        installer.install(mockk(relaxed = true), mockk(relaxed = true))

        assertEquals(9, captured.size)
        assertEquals(
            setOf(
                ChatMessageSentEvent::class.qualifiedName,
                ChatMessageReactionAddedEvent::class.qualifiedName,
                PrayerReactionAddedEvent::class.qualifiedName,
                PrayerCommentAddedEvent::class.qualifiedName,
                ChatChannelInvitationSentEvent::class.qualifiedName,
                ChatChannelJoinedEvent::class.qualifiedName,
                ProfileRelationshipRequested::class.qualifiedName,
                ProfileRelationshipRequestApproved::class.qualifiedName,
                ChatMentionEvent::class.qualifiedName,
            ),
            captured.map { it.acceptedInputType }.toSet(),
        )
        assertTrue(captured.all { it.triggered })
        assertTrue(
            captured.filterNot {
                it.name in setOf(
                    DefaultSocialNotificationPipelinesInstaller.CHAT_REACTION_PIPELINE,
                    DefaultSocialNotificationPipelinesInstaller.PRAYER_REACTION_PIPELINE,
                    DefaultSocialNotificationPipelinesInstaller.PRAYER_COMMENT_PIPELINE,
                )
            }
                .all { it.tags == listOf("Social", "Notifications", "Email", "Push") },
        )
        assertTrue(
            captured.filter {
                it.name in setOf(
                    DefaultSocialNotificationPipelinesInstaller.CHAT_REACTION_PIPELINE,
                    DefaultSocialNotificationPipelinesInstaller.PRAYER_REACTION_PIPELINE,
                    DefaultSocialNotificationPipelinesInstaller.PRAYER_COMMENT_PIPELINE,
                )
            }.all { it.tags == listOf("Social", "Notifications", "Push") },
        )
        assertTrue(captured.all { it.edges.single().targetPort == "event" })
        assertEquals(1, captured.sumOf { it.nodes.filterIsInstance<ChatMessageNotificationNode>().size })
        assertEquals(1, captured.sumOf { it.nodes.filterIsInstance<ChatMessageReactionNotificationNode>().size })
        assertEquals(1, captured.sumOf { it.nodes.filterIsInstance<PrayerReactionNotificationNode>().size })
        assertEquals(1, captured.sumOf { it.nodes.filterIsInstance<PrayerCommentNotificationNode>().size })
        assertEquals(1, captured.sumOf { it.nodes.filterIsInstance<ChatChannelInvitationNotificationNode>().size })
        assertEquals(1, captured.sumOf { it.nodes.filterIsInstance<ChatChannelJoinedNotificationNode>().size })
        assertEquals(1, captured.sumOf { it.nodes.filterIsInstance<ProfileRelationshipRequestedNotificationNode>().size })
        assertEquals(1, captured.sumOf { it.nodes.filterIsInstance<ProfileRelationshipAddedNotificationNode>().size })
        assertEquals(1, captured.sumOf { it.nodes.filterIsInstance<ChatMentionNotificationNode>().size })
        captured.flatMap { it.nodes }.filterIsInstance<ChatMessageNotificationNode>().single().also {
            assertTrue(it.email)
            assertTrue(it.push)
            assertEquals("bosca-messages", it.project)
            assertEquals("chat-message", it.template)
        }
        captured.flatMap { it.nodes }.filterIsInstance<ChatMessageReactionNotificationNode>().single().also {
            assertTrue(it.push)
            assertEquals("bosca-messages", it.project)
            assertEquals("chat-reaction", it.template)
        }
        captured.flatMap { it.nodes }.filterIsInstance<PrayerReactionNotificationNode>().single().also {
            assertTrue(it.push)
            assertEquals("bosca-messages", it.project)
            assertEquals("prayer-reaction", it.template)
        }
        captured.flatMap { it.nodes }.filterIsInstance<PrayerCommentNotificationNode>().single().also {
            assertTrue(it.push)
            assertEquals("bosca-messages", it.project)
            assertEquals("prayer-comment", it.template)
        }
    }

    @Test
    fun `preserves operator-owned pipelines with matching names`() = runTest {
        val (installer, captured) = installer(
            listOf(DefaultSocialNotificationPipelinesInstaller.CHAT_MESSAGE_PIPELINE),
        )

        installer.install(mockk(relaxed = true), mockk(relaxed = true))

        assertEquals(8, captured.size)
        assertTrue(captured.none { it.name == DefaultSocialNotificationPipelinesInstaller.CHAT_MESSAGE_PIPELINE })
    }
}
