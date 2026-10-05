import gql from 'graphql-tag'
import { print, type DocumentNode } from 'graphql'

const channelsGql = gql`
  query GetCollabDockChannels {
    profiles {
      current {
        id
        name
        chat {
          channels {
            id
            name
            type
            objectType
            objectId
            members {
              profileId
              role
              lastReadSequence
              profile {
                id
                name
              }
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
  query GetCollabDockMessages($channelId: UUID!, $limit: Int) {
    chat {
      channel(id: $channelId) {
        id
        name
        type
        members {
          profileId
          role
          lastReadSequence
          profile {
            id
            name
          }
        }
        messages(limit: $limit) {
          sequence
          timestamp
          senderId
          clientId
          sender {
            id
            name
          }
          content {
            type
            content
          }
          parentSequence
          reactions {
            emoji
            profileId
          }
        }
      }
    }
  }
`

const subscribeMessagesGql = gql`
  subscription OnCollabDockMessage($channelId: UUID!) {
    chatMessage(channelId: $channelId) {
      channelId
      message {
        sequence
        timestamp
        senderId
        clientId
        sender {
          id
          name
        }
        content {
          type
          content
        }
        parentSequence
        reactions {
          emoji
          profileId
        }
      }
    }
  }
`

const sendMessageGql = gql`
  mutation CollabDockSendMessage(
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
  mutation CollabDockAddReaction($channelId: UUID!, $sequence: Long!, $emoji: String!) {
    chat {
      addReaction(channelId: $channelId, sequence: $sequence, emoji: $emoji)
    }
  }
`

const removeReactionGql = gql`
  mutation CollabDockRemoveReaction($channelId: UUID!, $sequence: Long!, $emoji: String!) {
    chat {
      removeReaction(channelId: $channelId, sequence: $sequence, emoji: $emoji)
    }
  }
`

const updateLastReadGql = gql`
  mutation CollabDockUpdateLastRead($channelId: UUID!, $sequence: Long!) {
    chat {
      updateLastRead(channelId: $channelId, sequence: $sequence)
    }
  }
`

interface RawReaction {
  emoji: string
  profileId: string
}

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
  id: string
  name: string
  type: string
  objectType: string | null
  objectId: string | null
  members: ChannelMember[]
  messages: Array<{ sequence: number }>
}

interface AggregatedReaction {
  emoji: string
  count: number
  active: boolean
}

const AVATAR_COLORS = ['#ff9b5c', '#9d7cff', '#5ec5ff', '#34d99a', '#ff7ac6', '#ffb547', '#e5484d', '#3a86ff']

function resolveQuery(query: string | DocumentNode): string {
  return typeof query === 'string' ? query : print(query)
}

/**
 * Manages all data and operations for the CollabDock sidebar.
 * Fetches channels and messages via the chat GraphQL API, receives
 * real-time updates via WebSocket subscription, and handles sending
 * and reactions.
 */
export function useCollabDock() {
  const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
  const toast = useToast()

  const activeChannelId = ref<string | null>(null)
  const currentProfileId = ref<string | null>(null)

  const { data: channelsData, refresh: refreshChannels, status: channelsStatus } = useAsyncQuery<{
    profiles: {
      current: Array<{
        id: string
        name: string
        chat: { channels: RawChannel[] } | null
      }> | null
    }
  }>('collabdock-channels', channelsGql, {}, { server: false })

  watch(channelsData, (data) => {
    const profiles = data?.profiles?.current
    if (profiles?.length) {
      currentProfileId.value = profiles[0]!.id
    }
  }, { immediate: true })

  const channels = computed(() => {
    const profiles = channelsData.value?.profiles?.current
    if (!profiles?.length) return []
    const pid = currentProfileId.value
    return (profiles[0]!.chat?.channels ?? []).map(c => {
      const latestSeq = c.messages?.[0]?.sequence ?? 0
      const myMembership = pid ? c.members.find(m => m.profileId === pid) : null
      const lastRead = myMembership?.lastReadSequence ?? 0
      return {
        id: c.id,
        name: c.name,
        unreadCount: latestSeq > lastRead ? latestSeq - lastRead : 0,
      }
    })
  })

  watch(channels, (chs) => {
    if (!activeChannelId.value && chs.length > 0) {
      activeChannelId.value = chs[0]!.id
    }
  }, { immediate: true })

  const { data: msgData, refresh: refreshMessages, status: messagesStatus } = useAsyncQuery<{
    chat: {
      channel: {
        id: string
        name: string
        type: string
        members: ChannelMember[]
        messages: RawMessage[]
      } | null
    }
  }>('collabdock-messages', messagesGql, {
    // `$channelId` is `UUID!` — resolve to undefined (skip the fetch) while no
    // channel is selected; sending the ref's initial null fails validation.
    channelId: computed(() => activeChannelId.value ?? undefined),
    limit: 50,
  }, { server: false })

  // ── WebSocket subscription ──────────────────────────────────────────
  let ws: WebSocket | undefined
  let subIdCounter = 0
  let currentSubId: string | null = null
  let wsReady = false
  let pendingChannelId: string | null = null

  async function connectWs() {
    if (import.meta.server) return

    const config = useRuntimeConfig()
    const wsBase = (config.public as { wsUrl?: string }).wsUrl
      || `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
    const url = `${wsBase}/graphqlws`

    let connectionParams: Record<string, unknown> = {}
    try {
      const { $auth } = useNuxtApp()
      if ($auth) {
        const headers = await $auth.getAuthHeaders()
        const token = headers?.['Authorization']?.replace('Bearer ', '')
        if (token) connectionParams = { authToken: token }
      }
    } catch { /* auth not ready */ }

    ws = new WebSocket(url, 'graphql-transport-ws')

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
          if (msg.payload?.data?.chatMessage) {
            onSubscriptionMessage(msg.payload.data.chatMessage)
          }
          break
        case 'error':
          console.error('CollabDock subscription error', msg.payload)
          break
      }
    }

    ws.onclose = () => {
      wsReady = false
      currentSubId = null
      setTimeout(() => {
        if (activeChannelId.value) connectWs()
      }, 3000)
    }

    ws.onerror = (err) => {
      console.error('CollabDock WebSocket error', err)
    }
  }

  function subscribeTo(channelId: string) {
    if (!currentProfileId.value) {
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
        query: resolveQuery(subscribeMessagesGql),
        variables: { channelId },
      },
    }))
  }

  function onSubscriptionMessage(event: { channelId: string; message: RawMessage }) {
    if (event.channelId !== activeChannelId.value) return
    refreshMessages()
  }

  watch([activeChannelId, currentProfileId], ([id]) => {
    if (id) subscribeTo(id)
  })

  // ── Aggregation ─────────────────────────────────────────────────────

  function aggregateReactions(reactions: RawReaction[], profileId: string | null): AggregatedReaction[] {
    const grouped = new Map<string, { count: number; active: boolean }>()
    for (const r of reactions) {
      const existing = grouped.get(r.emoji)
      if (existing) {
        existing.count++
        if (r.profileId === profileId) existing.active = true
      } else {
        grouped.set(r.emoji, { count: 1, active: r.profileId === profileId })
      }
    }
    return [...grouped.entries()].map(([emoji, { count, active }]) => ({ emoji, count, active }))
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

  const allMessages = computed(() => {
    const raw = msgData.value?.chat?.channel?.messages ?? []
    return raw.map(m => {
      const { text, attachments } = parseContent(m.content)
      return {
        sequence: m.sequence,
        senderName: m.sender?.name ?? 'Unknown',
        senderId: m.senderId,
        timestamp: m.timestamp,
        text,
        attachments,
        reactions: aggregateReactions(m.reactions, currentProfileId.value),
        parentSequence: m.parentSequence,
      }
    })
  })

  const messages = computed(() =>
    allMessages.value
      .filter(m => m.parentSequence == null)
      .map(m => ({
        ...m,
        replyCount: allMessages.value.filter(r => r.parentSequence === m.sequence).length,
      })),
  )

  const threadSequence = ref<number | null>(null)

  const threadParent = computed(() => {
    if (threadSequence.value == null) return null
    return messages.value.find(m => m.sequence === threadSequence.value) ?? null
  })

  const threadReplies = computed(() => {
    if (threadSequence.value == null) return []
    return allMessages.value.filter(m => m.parentSequence === threadSequence.value)
  })

  const channelMeta = computed(() => {
    const ch = msgData.value?.chat?.channel
    return {
      name: ch?.name ?? '',
      memberCount: ch?.members?.length ?? 0,
    }
  })

  const isLoading = computed(() => channelsStatus.value === 'pending' || messagesStatus.value === 'pending')

  function selectChannel(id: string) {
    activeChannelId.value = id
  }

  async function sendMessage(text: string, attachments?: Array<{ type: string; id: string; name: string }>, parentSequence?: number | null) {
    if (!activeChannelId.value || !currentProfileId.value) return
    const contentItems: Array<{ type: string; content: string }> = []
    if (text) contentItems.push({ type: 'TEXT', content: text })
    if (attachments?.length) {
      for (const a of attachments) {
        contentItems.push({ type: 'METADATA', content: JSON.stringify({ type: a.type, id: a.id, name: a.name }) })
      }
    }
    if (!contentItems.length) return
    const vars: Record<string, unknown> = {
      channelId: activeChannelId.value,
      clientId: crypto.randomUUID(),
      content: contentItems,
    }
    if (parentSequence != null) vars.parentSequence = parentSequence
    try {
      await gqlMutation(sendMessageGql, vars)
    } catch {
      toast.error('Failed to send message')
    }
  }

  async function toggleReaction(sequence: number, emoji: string) {
    if (!activeChannelId.value || !currentProfileId.value) return
    const msg = messages.value.find(m => m.sequence === sequence)
    const alreadyReacted = msg?.reactions.some(r => r.emoji === emoji && r.active)
    try {
      await gqlMutation(alreadyReacted ? removeReactionGql : addReactionGql, {
        channelId: activeChannelId.value,
        sequence,
        emoji,
      })
      refreshMessages()
    } catch {
      toast.error('Failed to update reaction')
    }
  }

  function markAsRead() {
    if (!activeChannelId.value || !currentProfileId.value) return
    const allMsgs = allMessages.value
    if (!allMsgs.length) return
    const lastSeq = Math.max(...allMsgs.map(m => m.sequence))
    gqlMutation(updateLastReadGql, {
      channelId: activeChannelId.value,
      sequence: lastSeq,
    }).then(() => refreshChannels()).catch(() => {})
  }

  watch(allMessages, (msgs) => {
    if (msgs.length) markAsRead()
  })

  function senderColor(name: string): string {
    const hash = [...name].reduce((a, c) => a + c.charCodeAt(0), 0)
    return AVATAR_COLORS[hash % AVATAR_COLORS.length]!
  }

  onMounted(() => {
    connectWs()
  })

  onUnmounted(() => {
    if (ws && ws.readyState <= WebSocket.OPEN) {
      ws.close()
    }
  })

  return {
    channels,
    activeChannelId,
    messages,
    allMessages,
    channelMeta,
    isLoading,
    selectChannel,
    sendMessage,
    toggleReaction,
    refreshChannels,
    refreshMessages,
    senderColor,
    threadSequence,
    threadParent,
    threadReplies,
  }
}
