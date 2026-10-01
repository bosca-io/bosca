package bosca.collaboration.service

import bosca.chat.events.CHAT_MESSAGE_SENT_TOPIC
import bosca.chat.events.ChatMessageSentEvent
import bosca.chat.service.ChatService
import bosca.collaboration.bridge.BridgeService
import bosca.collaboration.events.ChatKitMessageEvent
import bosca.collaboration.events.ChatMentionEvent
import bosca.collaboration.events.ChatMessageBridgeEvent
import bosca.collaboration.events.ChatMessageFederationEvent
import bosca.collaboration.events.FederationTarget
import bosca.collaboration.events.dispatch
import bosca.collaboration.federation.FederationService
import bosca.collaboration.federation.FederationSyncDirection
import bosca.di.ObjectProvider
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Bridges the generic chat message-sent event into collaboration-specific
 * dispatches: mention notifications, Kit AI agent triggers, and outbound
 * bridge relay. Lives in the collaboration module so the chat module stays
 * unaware of these features — chat publishes [ChatMessageSentEvent] to a
 * pubsub topic, and this listener fans out from there.
 *
 * The listener runs a long-lived coroutine on its own [SupervisorJob] scope
 * so a transient pubsub hiccup just retries with backoff rather than tearing
 * down the whole subscription tree.
 */
class ChatMessageDispatchListener(
    private val pubSubService: PubSubService,
    private val mentionService: MentionService,
    private val bridgeService: BridgeService,
    private val federationService: ObjectProvider<FederationService>,
    private val chatService: ObjectProvider<ChatService>,
    private val profileService: ObjectProvider<ProfileService>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                try {
                    pubSubService.subscribe(CHAT_MESSAGE_SENT_TOPIC, ChatMessageSentEvent.serializer()).collect { msg ->
                        runCatching { handle(msg.message) }
                            .onFailure { log.error("Chat message dispatch failed for sequence {}", msg.message.sequence, it) }
                    }
                } catch (e: Exception) {
                    log.error("Chat message-sent subscription failed, retrying in 5s", e)
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    /**
     * Inspects an incoming chat message and dispatches the collaboration
     * sub-events that apply: Kit (when @kit or /kit appears), bridge (when
     * the channel has active bindings and the message did not originate
     * from a bridge), and mention notifications (for eligible mentioned
     * profiles who are not already channel members).
     */
    internal suspend fun handle(event: ChatMessageSentEvent) {
        dispatchKitIfRequested(event)
        dispatchBridgeIfRouted(event)
        dispatchFederationIfRouted(event)
        dispatchMentionNotificationsIfNeeded(event)
    }

    private suspend fun dispatchKitIfRequested(event: ChatMessageSentEvent) {
        val slashCommand = parseKitSlashCommand(event.content)
        val mentionsKit = slashCommand == null && containsKitMention(event.content)
        if (slashCommand == null && !mentionsKit) return
        ChatKitMessageEvent(
            channelId = event.channelId,
            senderId = event.senderId,
            sequence = event.sequence,
            content = event.content,
            slashCommand = slashCommand,
            attributes = event.attributes,
        ).dispatch()
    }

    private suspend fun dispatchBridgeIfRouted(event: ChatMessageSentEvent) {
        if (originatedFromBridge(event)) return
        val bindings = bridgeService.getBindingsForChannel(event.channelId)
        if (bindings.isEmpty()) return
        val senderName = resolveDisplayName(event.senderId) ?: event.senderId.toString()
        ChatMessageBridgeEvent(
            channelId = event.channelId,
            senderId = event.senderId,
            sequence = event.sequence,
            content = event.content,
            senderName = senderName,
            attributes = event.attributes,
        ).dispatch()
    }

    private suspend fun dispatchFederationIfRouted(event: ChatMessageSentEvent) {
        if (originatedFromFederation(event)) return
        val targets = federationService.get().getFederatedChannels(event.channelId)
            .filter { it.syncDirection != FederationSyncDirection.INBOUND }
            .map { FederationTarget(it.peerId, it.remoteChannelId, it.syncDirection) }
        if (targets.isEmpty()) return
        val senderName = resolveDisplayName(event.senderId) ?: event.senderId.toString()
        ChatMessageFederationEvent(
            channelId = event.channelId,
            senderId = event.senderId,
            sequence = event.sequence,
            content = event.content,
            senderName = senderName,
            targets = targets,
            attributes = event.attributes,
        ).dispatch()
    }

    private suspend fun dispatchMentionNotificationsIfNeeded(event: ChatMessageSentEvent) {
        val mentioned = mentionService.extractMentions(event.content)
        if (mentioned.isEmpty()) return
        val validation = mentionService.validateMentions(event.channelId, mentioned)
        val recipients = validation.reachableNonMembers
        if (recipients.isEmpty()) return
        val senderName = resolveDisplayName(event.senderId) ?: event.senderId.toString()
        val channelName = chatService.get().getById(event.channelId)?.name ?: ""
        ChatMentionEvent(
            channelId = event.channelId,
            senderId = event.senderId,
            sequence = event.sequence,
            recipientProfileIds = recipients,
            senderName = senderName,
            channelName = channelName,
            content = event.content,
        ).dispatch()
    }

    /**
     * Detects a `/kit <command> [args]` slash command in the first TEXT
     * block. Mirrors the standalone [parseSlashCommand] helper but only
     * considers commands targeting the kit agent.
     */
    internal fun parseKitSlashCommand(content: List<MessageContent>): String? {
        val parsed = parseSlashCommand(content) ?: return null
        if (!parsed.agent.equals("kit", ignoreCase = true)) return null
        return parsed.command
    }

    internal fun containsKitMention(content: List<MessageContent>): Boolean {
        for (block in content) {
            when (block.type) {
                MessageContentType.TEXT, MessageContentType.HTML -> {
                    if (KIT_MENTION_PATTERN.containsMatchIn(block.content)) return true
                }
                else -> {}
            }
        }
        return false
    }

    /**
     * Returns true when the message attributes carry the `bridge` source
     * marker added by inbound webhook routes. Used to break echo loops
     * when an incoming bridged message would otherwise be re-dispatched
     * back through the bridge.
     */
    internal fun originatedFromBridge(event: ChatMessageSentEvent): Boolean =
        sourceMarker(event) == "bridge"

    /**
     * Returns true when the message attributes carry the `federation`
     * source marker added by [FederationInboundListener]. Used to break
     * echo loops between federation peers.
     */
    internal fun originatedFromFederation(event: ChatMessageSentEvent): Boolean =
        sourceMarker(event) == "federation"

    private fun sourceMarker(event: ChatMessageSentEvent): String? {
        val attributes = event.attributes as? JsonObject ?: return null
        return (attributes["source"] as? JsonPrimitive)?.let {
            runCatching { it.content }.getOrNull()
        }
    }

    private suspend fun resolveDisplayName(profileId: UUID): String? {
        return runCatching { profileService.get().getById(profileId).name }.getOrNull()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChatMessageDispatchListener::class.java)
        // The leading lookbehind matches start-of-string, whitespace, or any
        // non-word character (covers HTML angle brackets, parens, etc.) so
        // mentions inside markup also light up. We still require a word
        // boundary or whitespace after `kit` so `@kitexample` does not match.
        private val KIT_MENTION_PATTERN = Regex("(?i)(?:^|[^A-Za-z0-9_])@kit(?=$|\\s|[^A-Za-z0-9_])")
        const val RETRY_DELAY_MS = 5000L
    }
}
