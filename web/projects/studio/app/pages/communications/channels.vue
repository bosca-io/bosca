<script setup lang="ts">
import gql from 'graphql-tag'
import { print, type DocumentNode } from 'graphql'
import ChatMessage from '~/components/collaboration/ChatMessage.vue'
import ComposeArea from '~/components/collaboration/ComposeArea.vue'
import ThreadPanel from '~/components/collaboration/ThreadPanel.vue'
import NewChannelModal from '~/components/communications/NewChannelModal.vue'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const route = useRoute()

function resolveGql(query: string | DocumentNode): string {
  return typeof query === 'string' ? query : print(query)
}

const channelsGql = gql`
  query GetMsgChannels {
    profiles {
      current {
        id
        name
        chat {
          channels {
            id
            name
            type
            members {
              profileId
              role
              lastReadSequence
              profile { id name }
            }
            messages(limit: 1) {
              sequence
            }
          }
        }
      }
    }
  }
`

const messagesGql = gql`
  query GetMsgMessages($channelId: UUID!, $limit: Int) {
    chat {
      channel(id: $channelId) {
        id
        name
        type
        members {
          profileId
          role
          profile { id name }
        }
        messages(limit: $limit) {
          sequence
          timestamp
          senderId
          clientId
          sender { id name }
          content { type content }
          parentSequence
          reactions { emoji profileId }
        }
      }
    }
  }
`

const sendMessageGql = gql`
  mutation MsgSendMessage(
    $channelId: UUID!
    $clientId: UUID!
    $content: [MessageContentInput!]!
    $parentSequence: Long
  ) {
    chat {
      sendMessage(
        channelId: $channelId
        clientId: $clientId
        content: $content
        parentSequence: $parentSequence
      )
    }
  }
`

const addReactionGql = gql`
  mutation MsgAddReaction($channelId: UUID!, $sequence: Long!, $emoji: String!) {
    chat { addReaction(channelId: $channelId, sequence: $sequence, emoji: $emoji) }
  }
`

const removeReactionGql = gql`
  mutation MsgRemoveReaction($channelId: UUID!, $sequence: Long!, $emoji: String!) {
    chat { removeReaction(channelId: $channelId, sequence: $sequence, emoji: $emoji) }
  }
`

const subscribeMessagesGql = gql`
  subscription OnMsgMessage($channelId: UUID!) {
    chatMessage(channelId: $channelId) {
      channelId
      message {
        sequence
        timestamp
        senderId
        clientId
        sender { id name }
        content { type content }
        parentSequence
        reactions { emoji profileId }
      }
    }
  }
`

interface RawReaction { emoji: string; profileId: string }
interface RawMessage {
  sequence: number
  timestamp: string
  senderId: string
  clientId: string | null
  sender: { id: string; name: string } | null
  content: Array<{ type: string; content: string }>
  parentSequence: number | null
  reactions: RawReaction[]
}
interface ChannelMember {
  profileId: string
  role: string
  lastReadSequence: number | null
  profile: { id: string; name: string }
}
interface RawChannel {
  id: string; name: string; type: string
  members: ChannelMember[]
  messages: Array<{ sequence: number }>
}

const sendTypingGql = gql`
  mutation SendTyping($channelId: UUID!, $isTyping: Boolean!) {
    chat { sendTyping(channelId: $channelId, isTyping: $isTyping) }
  }
`

const updateLastReadGql = gql`
  mutation UpdateLastRead($channelId: UUID!, $sequence: Long!) {
    chat { updateLastRead(channelId: $channelId, sequence: $sequence) }
  }
`

const currentProfileId = ref<string | null>(null)
const requestedChannelId = typeof route.query.channelId === 'string' ? route.query.channelId : null
const selectedChannelId = ref<string | null>(requestedChannelId)
const showNewChannel = ref(false)
useCreateFromQuery(() => { showNewChannel.value = true })
const settingsOpen = ref(false)

// Typing indicator state
const typingUsers = ref<Map<string, string>>(new Map())
let typingTimer: ReturnType<typeof setTimeout> | null = null

const typingDisplay = computed(() => {
  const names = Array.from(typingUsers.value.values()).filter(n => n !== currentProfileName.value)
  if (names.length === 0) return ''
  if (names.length === 1) return `${names[0]} is typing…`
  if (names.length === 2) return `${names[0]} and ${names[1]} are typing…`
  return `${names[0]} and ${names.length - 1} others are typing…`
})

const currentProfileName = computed(() => {
  const id = currentProfileId.value
  if (!id) return ''
  const member = channelMembers.value.find(m => m.profileId === id)
  return member?.profile?.name ?? ''
})

function onComposerTyping() {
  if (!selectedChannelId.value || !currentProfileId.value) return

  if (!typingTimer) {
    gqlMutation(sendTypingGql, {
      channelId: selectedChannelId.value,
      isTyping: true,
    }).catch(() => {})
  }

  if (typingTimer) clearTimeout(typingTimer)
  typingTimer = setTimeout(() => {
    typingTimer = null
    if (selectedChannelId.value && currentProfileId.value) {
      gqlMutation(sendTypingGql, {
        channelId: selectedChannelId.value,
        isTyping: false,
      }).catch(() => {})
    }
  }, 3000)
}

// Read tracking
function markAsRead() {
  if (!selectedChannelId.value || !currentProfileId.value) return
  const lastMsg = rawMessages.value[rawMessages.value.length - 1]
  if (!lastMsg) return
  gqlMutation(updateLastReadGql, {
    channelId: selectedChannelId.value,
    sequence: lastMsg.sequence,
  }).then(() => refreshChannels()).catch(() => {})
}

// Member list for mentions
const mentionMembers = computed(() =>
  channelMembers.value.map(m => ({
    id: m.profileId,
    name: m.profile?.name ?? m.profileId,
  })),
)

const { data: channelsData, refresh: refreshChannels } = useAsyncQuery<{
  profiles: { current: Array<{ id: string; name: string; chat: { channels: RawChannel[] } | null }> | null }
}>('msg-channels', channelsGql, {}, { server: false })

watch(channelsData, (data) => {
  const profiles = data?.profiles?.current
  if (profiles?.length) currentProfileId.value = profiles[0]!.id
}, { immediate: true })

const channels = computed(() => {
  const profiles = channelsData.value?.profiles?.current
  if (!profiles?.length) return []
  const pid = currentProfileId.value
  return (profiles[0]!.chat?.channels ?? []).map(c => {
    const latestSeq = c.messages?.[0]?.sequence ?? 0
    const myMembership = pid ? c.members.find(m => m.profileId === pid) : null
    const lastRead = myMembership?.lastReadSequence ?? 0
    const unreadCount = latestSeq > lastRead ? latestSeq - lastRead : 0
    return {
      id: c.id,
      name: c.name,
      type: c.type,
      memberCount: c.members.length,
      unreadCount,
    }
  })
})

watch(channels, (chs) => {
  if (requestedChannelId && chs.some(channel => channel.id === requestedChannelId)) {
    selectedChannelId.value = requestedChannelId
  }
  else if ((!selectedChannelId.value || !chs.some(channel => channel.id === selectedChannelId.value)) && chs.length > 0) {
    selectedChannelId.value = chs[0]!.id
  }
}, { immediate: true })

const { data: msgData, refresh: refreshMessages } = useAsyncQuery<{
  chat: {
    channel: {
      id: string; name: string; type: string
      members: ChannelMember[]
      messages: RawMessage[]
    } | null
  }
}>('msg-messages', messagesGql, {
  channelId: selectedChannelId,
  limit: 50,
}, { server: false })

const channelName = computed(() => msgData.value?.chat?.channel?.name ?? '')
const channelMembers = computed(() => msgData.value?.chat?.channel?.members ?? [])
const rawMessages = computed(() => msgData.value?.chat?.channel?.messages ?? [])

function aggregateReactions(reactions: RawReaction[]) {
  const grouped = new Map<string, { count: number; active: boolean }>()
  for (const r of reactions) {
    const existing = grouped.get(r.emoji)
    if (existing) {
      existing.count++
      if (r.profileId === currentProfileId.value) existing.active = true
    } else {
      grouped.set(r.emoji, { count: 1, active: r.profileId === currentProfileId.value })
    }
  }
  return [...grouped.entries()].map(([emoji, v]) => ({ emoji, ...v }))
}

function parseContent(content: Array<{ type: string; content: string }>) {
  const textParts: string[] = []
  const attachments: Array<{ type: string; id: string; name: string }> = []
  for (const c of content) {
    if (c.type === 'TEXT') {
      textParts.push(c.content)
    } else if (c.type === 'METADATA') {
      try {
        const parsed = JSON.parse(c.content)
        attachments.push({ type: parsed.type ?? 'metadata', id: parsed.id, name: parsed.name ?? 'Attachment' })
      } catch {
        textParts.push(c.content)
      }
    } else {
      textParts.push(c.content)
    }
  }
  return { text: textParts.join('\n'), attachments }
}

const messages = computed(() =>
  rawMessages.value
    .filter(m => m.parentSequence == null)
    .map(m => {
      const { text, attachments } = parseContent(m.content)
      return {
        sequence: m.sequence,
        senderName: m.sender?.name ?? 'Unknown',
        senderId: m.senderId,
        timestamp: m.timestamp,
        text,
        attachments,
        reactions: aggregateReactions(m.reactions),
        replyCount: rawMessages.value.filter(r => r.parentSequence === m.sequence).length,
      }
    }),
)

const AVATAR_COLORS = ['#ff9b5c', '#9d7cff', '#5ec5ff', '#34d99a', '#ff7ac6', '#ffb547', '#e5484d', '#3a86ff']
function senderColor(name: string): string {
  const hash = [...name].reduce((a, c) => a + c.charCodeAt(0), 0)
  return AVATAR_COLORS[hash % AVATAR_COLORS.length]!
}

const threadSequence = ref<number | null>(null)

const threadParent = computed(() => {
  if (threadSequence.value == null) return null
  return messages.value.find(m => m.sequence === threadSequence.value) ?? null
})

const threadReplies = computed(() => {
  if (threadSequence.value == null) return []
  return rawMessages.value
    .filter(m => m.parentSequence === threadSequence.value)
    .map(m => {
      const { text, attachments } = parseContent(m.content)
      return {
        id: String(m.sequence),
        sender: { name: m.sender?.name ?? 'Unknown', color: senderColor(m.sender?.name ?? 'Unknown') },
        timestamp: m.timestamp,
        text,
        attachments,
        reactions: aggregateReactions(m.reactions),
      }
    })
})

function onSelectChannel(id: string) {
  selectedChannelId.value = id
  threadSequence.value = null
}

const composeRef = ref<{ addAttachment: (attachment: { type: string; id: string; name: string }) => void } | null>(null)
const threadRef = ref<{ addAttachment: (attachment: { type: string; id: string; name: string }) => void } | null>(null)
const metadataPickerOpen = ref(false)
const collectionPickerOpen = ref(false)

function onMetadataSelected(meta: { id: string; name: string }) {
  metadataPickerOpen.value = false
  composeRef.value?.addAttachment({ type: 'metadata', id: meta.id, name: meta.name })
}

function onCollectionSelected(coll: { id: string; name: string }) {
  collectionPickerOpen.value = false
  composeRef.value?.addAttachment({ type: 'collection', id: coll.id, name: coll.name })
}

function onComposeOpenPicker(type: string) {
  if (type === 'metadata') metadataPickerOpen.value = true
  else if (type === 'collection') collectionPickerOpen.value = true
}

async function onSend(text: string, attachments?: Array<{ type: string; id: string; name: string }>) {
  if (!selectedChannelId.value || !currentProfileId.value) return

  const content: Array<{ type: string; content: string }> = []
  if (text) content.push({ type: 'TEXT', content: text })
  if (attachments) {
    for (const a of attachments) {
      content.push({ type: 'METADATA', content: JSON.stringify({ type: a.type, id: a.id, name: a.name }) })
    }
  }
  if (content.length === 0) return

  try {
    await gqlMutation(sendMessageGql, {
      channelId: selectedChannelId.value,
      clientId: crypto.randomUUID(),
      content,
    })
    // Refresh immediately so the sender sees their own message without waiting
    // on the chatMessage subscription round-trip (which only drives others' updates).
    await refreshMessages()
  } catch {
    toast.error('Failed to send message')
  }
}

async function onSendReply(text: string, attachments?: Array<{ type: string; id: string; name: string }>) {
  if (!selectedChannelId.value || !currentProfileId.value || threadSequence.value == null) return

  const content: Array<{ type: string; content: string }> = []
  if (text) content.push({ type: 'TEXT', content: text })
  if (attachments) {
    for (const a of attachments) {
      content.push({ type: 'METADATA', content: JSON.stringify({ type: a.type, id: a.id, name: a.name }) })
    }
  }
  if (content.length === 0) return

  try {
    await gqlMutation(sendMessageGql, {
      channelId: selectedChannelId.value,
      clientId: crypto.randomUUID(),
      content,
      parentSequence: threadSequence.value,
    })
    // Refresh immediately so the reply shows up for the sender; the subscription
    // round-trip is best-effort and only needs to cover other participants.
    await refreshMessages()
  } catch {
    toast.error('Failed to send reply')
  }
}

async function onReact(sequence: number, emoji: string) {
  if (!selectedChannelId.value || !currentProfileId.value) return
  const msg = messages.value.find(m => m.sequence === sequence)
  const alreadyReacted = msg?.reactions.some(r => r.emoji === emoji && r.active)
  try {
    await gqlMutation(alreadyReacted ? removeReactionGql : addReactionGql, {
      channelId: selectedChannelId.value,
      sequence,
      emoji,
    })
    await refreshMessages()
  } catch {
    toast.error('Failed to update reaction')
  }
}

function onChannelCreated(id: string) {
  refreshChannels()
  selectedChannelId.value = id
}

const messagesEl = ref<HTMLElement>()
watch(messages, () => {
  nextTick(() => {
    if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    markAsRead()
  })
})

// ── WebSocket subscription ──────────────────────────────────────────
let ws: WebSocket | undefined
let subIdCounter = 0
let currentSubId: string | null = null
let wsReady = false
let pendingChannelId: string | null = null

async function connectWs() {
  const config = useRuntimeConfig()
  const wsBase = (config.public as Record<string, unknown>).wsUrl as string | undefined
    || `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`

  let connectionParams: Record<string, unknown> = {}
  try {
    const { $auth } = useNuxtApp()
    if ($auth) {
      const headers = await $auth.getAuthHeaders()
      const token = headers?.['Authorization']?.replace('Bearer ', '')
      if (token) connectionParams = { authToken: token }
    }
  } catch { /* auth not ready */ }

  ws = new WebSocket(`${wsBase}/graphqlws`, 'graphql-transport-ws')

  ws.onopen = () => {
    ws!.send(JSON.stringify({ type: 'connection_init', payload: connectionParams }))
  }

  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data)
    switch (msg.type) {
      case 'connection_ack':
        wsReady = true
        if (pendingChannelId) {
          const channelId = pendingChannelId
          pendingChannelId = null
          subscribeTo(channelId)
        }
        break
      case 'ping':
        ws!.send(JSON.stringify({ type: 'pong' }))
        break
      case 'next':
        if (msg.payload?.data?.chatMessage) refreshMessages()
        break
      case 'error':
        console.error('Messaging subscription error', msg.payload)
        break
    }
  }

  ws.onclose = () => {
    wsReady = false
    currentSubId = null
    setTimeout(() => {
      if (selectedChannelId.value) connectWs()
    }, 3000)
  }
}

function subscribeTo(channelId: string) {
  const profileId = currentProfileId.value
  if (!profileId) {
    pendingChannelId = channelId
    return
  }
  if (!ws || ws.readyState !== WebSocket.OPEN || !wsReady) {
    pendingChannelId = channelId
    return
  }
  if (currentSubId) {
    ws.send(JSON.stringify({ id: currentSubId, type: 'complete' }))
  }
  subIdCounter++
  currentSubId = String(subIdCounter)
  ws.send(JSON.stringify({
    id: currentSubId,
    type: 'subscribe',
    payload: {
      query: resolveGql(subscribeMessagesGql),
      variables: { channelId, profileId },
    },
  }))
}

watch([selectedChannelId, currentProfileId], ([id]) => {
  if (id) subscribeTo(id)
})

onMounted(() => { connectWs() })
onUnmounted(() => {
  if (ws && ws.readyState <= WebSocket.OPEN) ws.close()
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :breadcrumb="buildBreadcrumb('Messaging', 'Channels')"
        title="Channels"
        :subtitle="channels.length ? `${channels.length} channel${channels.length === 1 ? '' : 's'}` : undefined"
        :accent="accent"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showNewChannel = true">
            New Channel
          </Button>
        </template>
      </PageHeader>
    </template>

    <div class="messaging-layout">
      <!-- Channel sidebar -->
      <div class="channel-sidebar">
        <div class="channel-sidebar-header">
          <span class="channel-sidebar-title">Channels</span>
          <button class="channel-sidebar-add" title="New channel" @click="showNewChannel = true">
            <Icon name="plus" :size="14" color="var(--fg-3)" />
          </button>
        </div>
        <div class="channel-list">
          <button
            v-for="c in channels"
            :key="c.id"
            class="channel-item"
            :class="{ active: c.id === selectedChannelId }"
            @click="onSelectChannel(c.id)"
          >
            <span class="channel-hash">#</span>
            <span class="channel-name" :class="{ 'channel-name--unread': c.unreadCount > 0 }">{{ c.name }}</span>
            <span v-if="c.unreadCount > 0" class="channel-unread-badge">{{ c.unreadCount }}</span>
            <span v-else class="channel-member-count mono">{{ c.memberCount }}</span>
          </button>
        </div>
        <div v-if="!channels.length" class="channel-empty">
          <Icon name="message" :size="20" color="var(--fg-4)" />
          <span>No channels yet</span>
          <Button
            size="sm"
            icon="plus"
            :accent="accent"
            @click="showNewChannel = true">Create one</Button>
        </div>
      </div>

      <!-- Messages pane -->
      <div v-if="selectedChannelId" class="message-pane">
        <div class="message-pane-header">
          <span class="message-pane-hash">#</span>
          <span class="message-pane-name">{{ channelName }}</span>
          <span class="message-pane-meta mono">{{ channelMembers.length }} members</span>
          <span style="flex: 1" />
          <button class="pane-settings-btn" title="Channel settings" @click="settingsOpen = true">
            <Icon name="gear" :size="14" />
          </button>
        </div>

        <div ref="messagesEl" class="message-list">
          <template v-if="messages.length">
            <ChatMessage
              v-for="msg in messages"
              :key="msg.sequence"
              :sender="{ name: msg.senderName, color: senderColor(msg.senderName) }"
              :timestamp="msg.timestamp"
              :text="msg.text"
              :attachments="msg.attachments"
              :reactions="msg.reactions"
              :reply-count="msg.replyCount"
              @reply="threadSequence = msg.sequence"
              @react="onReact(msg.sequence, $event)"
            />
          </template>
          <div v-else class="message-empty">
            <Icon name="message" :size="28" color="var(--fg-4)" />
            <span>No messages yet. Start the conversation.</span>
          </div>
        </div>

        <div v-if="typingDisplay" class="typing-indicator">{{ typingDisplay }}</div>
        <ComposeArea
          ref="composeRef"
          :members="mentionMembers"
          @send="onSend"
          @typing="onComposerTyping"
          @open-picker="onComposeOpenPicker"
        />
      </div>

      <!-- No channel selected -->
      <div v-else class="message-pane-empty">
        <Icon name="message" :size="32" color="var(--fg-4)" />
        <span>Select a channel to start chatting</span>
      </div>

      <!-- Thread panel -->
      <ThreadPanel
        v-if="threadParent"
        ref="threadRef"
        :parent-message="{
          sender: { name: threadParent.senderName, color: senderColor(threadParent.senderName) },
          timestamp: threadParent.timestamp,
          text: threadParent.text,
        }"
        :replies="threadReplies"
        @close="threadSequence = null"
        @send="onSendReply"
        @open-picker="onComposeOpenPicker"
        @react="(seq: string, emoji: string) => onReact(Number(seq), emoji)"
      />
    </div>

    <NewChannelModal
      v-if="showNewChannel && currentProfileId"
      :accent="accent"
      @close="showNewChannel = false"
      @created="onChannelCreated"
    />

    <ChannelSettings
      v-if="settingsOpen && selectedChannelId"
      :channel-id="selectedChannelId"
      :channel-name="channelName"
      @close="settingsOpen = false"
    />

    <MetadataPickerModal
      v-if="metadataPickerOpen"
      @select="onMetadataSelected"
      @close="metadataPickerOpen = false"
    />

    <CollectionPickerModal
      v-if="collectionPickerOpen"
      @select="onCollectionSelected"
      @close="collectionPickerOpen = false"
    />
  </PageShell>
</template>

<style scoped>
:deep(.page-content) {
  padding: 0 22px 22px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.messaging-layout {
  display: flex;
  flex: 1;
  min-height: 0;
  border-radius: var(--r-md);
  border: 1px solid var(--line);
  overflow: hidden;
}

/* ── Channel sidebar ─────────────────────────────────────────────────── */
.channel-sidebar {
  width: 240px;
  flex: 0 0 240px;
  border-right: 1px solid var(--line);
  background: var(--bg-1);
  display: flex;
  flex-direction: column;
}

.channel-sidebar-header {
  display: flex;
  align-items: center;
  padding: 0 14px;
  height: 48px;
  border-bottom: 1px solid var(--line);
  gap: 8px;
}

.channel-sidebar-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-2);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  flex: 1;
}

.channel-sidebar-add {
  width: 24px;
  height: 24px;
  border-radius: var(--r-xs);
  display: flex;
  align-items: center;
  justify-content: center;
}

.channel-sidebar-add:hover {
  background: var(--bg-3);
}

.channel-list {
  flex: 1;
  overflow: auto;
  padding: 6px;
}

.channel-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: var(--r-xs);
  font-size: 13px;
  color: var(--fg-2);
  text-align: left;
  transition: background 0.12s, color 0.12s;
}

.channel-item:hover {
  background: color-mix(in oklch, var(--fg-2) 8%, transparent);
}

.channel-item.active {
  background: color-mix(in oklch, var(--fg-2) 12%, transparent);
  color: var(--fg-0);
}

.channel-hash {
  color: var(--fg-3);
  font-weight: 600;
}

.channel-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.channel-member-count {
  font-size: 10.5px;
  color: var(--fg-4);
}

.channel-name--unread {
  font-weight: 600;
  color: var(--fg-0);
}

.channel-unread-badge {
  font-size: 10.5px;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--brand-2);
  color: #fff;
  font-weight: 600;
  min-width: 18px;
  text-align: center;
}

.channel-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 20px;
  font-size: 12px;
  color: var(--fg-3);
}

/* ── Message pane ────────────────────────────────────────────────────── */
.message-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  position: relative;
}

.message-pane-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 18px;
  height: 48px;
  border-bottom: 1px solid var(--line);
}

.message-pane-hash {
  color: var(--fg-3);
  font-weight: 600;
  font-size: 14px;
}

.message-pane-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.message-pane-meta {
  font-size: 11px;
  color: var(--fg-3);
}

.pane-settings-btn {
  width: 28px; height: 28px; background: none; border: none;
  color: var(--fg-3); cursor: pointer; border-radius: var(--r-sm);
  display: flex; align-items: center; justify-content: center;
}
.pane-settings-btn:hover { color: var(--fg-0); background: var(--bg-2); }

.message-list {
  flex: 1;
  overflow: auto;
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.message-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 13px;
  color: var(--fg-3);
}

.message-pane-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 13px;
  color: var(--fg-3);
}

.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 20;
  background: color-mix(in oklch, var(--bg-0) 90%, transparent);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 14px;
  color: var(--fg-2);
  border: 2px dashed var(--brand-2);
  border-radius: var(--r-md);
  pointer-events: none;
}

.typing-indicator {
  padding: 0 14px 4px;
  font-size: 11px;
  color: var(--fg-3);
  font-style: italic;
  animation: typing-pulse 1.5s ease-in-out infinite;
}

@keyframes typing-pulse {
  0%, 100% { opacity: 0.5; }
  50% { opacity: 1; }
}
</style>
