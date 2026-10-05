<script setup lang="ts">
import gql from 'graphql-tag'

definePageMeta({
  // Cross-fade with the Kit landing page so arriving with a handed-off message
  // eases in instead of hard-cutting. See .page-fade-* in main.css.
  pageTransition: { name: 'page-fade', mode: 'out-in' },
})

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, useSubscription, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const sessionId = computed(() => route.params.id as string)

const sessionGql = gql`
  query GetChatSession($id: UUID!) {
    chatSessions {
      session(id: $id) {
        id
        agentKey
        title
        processing
        status
        created
        modified
        messages { id sessionId author created event }
      }
    }
  }
`

const sendGql = gql`
  mutation SendMessage($id: UUID!, $message: ChatMessageInput!) {
    chatSessions { send(sessionId: $id, message: $message) }
  }
`

const subscribeHistoryGql = gql`
  subscription AiChatHistory($id: UUID!) {
    aiChatHistory(sessionId: $id) {
      id
      sessionId
      author
      created
      event
    }
  }
`

const subscribeProcessingGql = gql`
  subscription AiChatProcessing($id: UUID!) {
    aiChatProcessing(sessionId: $id)
  }
`

interface ChatSessionMessage { id: string; sessionId: string; author: string; created: string; event: unknown }
interface ChatSession { id: string; agentKey: string; title: string; processing: boolean; status: string; created: string; modified: string; messages: ChatSessionMessage[] }

const { data, status: queryStatus, refresh } = useAsyncQuery<{
  chatSessions: { session: ChatSession | null }
}>('kit-session', sessionGql, { id: sessionId }, { server: false })

const session = computed(() => data.value?.chatSessions?.session)
const title = computed(() => session.value?.title ?? 'Chat')

interface ChatTextMessage {
  id: string
  kind: 'text'
  role: 'user' | 'assistant'
  text: string
}

interface ChatVizMessage {
  id: string
  kind: 'viz'
  name: string
  vizType: string
  configuration: Record<string, unknown>
  data: Record<string, unknown>[]
  /** The SQL statement that produced the data — the answer's source, shown on demand. */
  sourceQuery?: string
  savedQueryId?: string
  visualizationId?: string
  artifacts: AnalyticsArtifactLink[]
  investigation: AnalyticsInvestigationStep[]
}

interface AnalyticsArtifactLink {
  kind: 'Saved query' | 'Visualization' | 'Dashboard'
  label: string
  to: string
}

interface AnalyticsInvestigationStep {
  sequence: number
  kind: 'DISCOVERY' | 'QUERY' | 'SAVED_QUERY' | 'ARTIFACT'
  tool: string
  sql?: string
  resultSummary: string
  startedAt: string
  purpose?: string
  conclusion?: string
}

type ChatMessage = ChatTextMessage | ChatVizMessage

interface EventPart { text?: string; thought?: boolean; functionCall?: unknown; functionResponse?: unknown }
interface ParsedEvent { type?: string; parts: EventPart[]; author?: string }

function parseEvent(raw: unknown): ParsedEvent | null {
  const event = typeof raw === 'string' ? JSON.parse(raw) : raw
  if (!event?.parts) return null
  if (event.author === 'tool-request' || event.author === 'tool-display') return null
  if (event.type === '"ai.koog.prompt.message.Message.Assistant') {
    event.type = 'assistant'
  }
  if (event.type === '"ai.koog.prompt.message.Message.User') {
    event.type = 'user'
  }
  return event as ParsedEvent
}

function parseMessage(m: ChatSessionMessage | null): ChatMessage | null {
  if (!m) return null
  const raw = typeof m.event === 'string' ? JSON.parse(m.event) : m.event
  // A visualization turn (Kit's analytics result) — render it inline as a chart/table.
  if ((raw as { type?: string })?.type === 'tool-display') {
    const e = raw as {
      title?: string
      visualizationType?: string
      configuration?: Record<string, unknown>
      data?: Record<string, unknown>[]
      sourceQuery?: string
      savedQueryId?: string
      savedQueryKey?: string
      visualizationId?: string
      dashboardId?: string
      investigation?: AnalyticsInvestigationStep[]
    }
    const artifacts: AnalyticsArtifactLink[] = []
    if (e.savedQueryId) artifacts.push({ kind: 'Saved query', label: e.savedQueryKey || 'Saved query', to: `/analytics/queries/${e.savedQueryId}` })
    if (e.visualizationId) artifacts.push({ kind: 'Visualization', label: 'Visualization', to: `/analytics/visualizations/${e.visualizationId}` })
    if (e.dashboardId) artifacts.push({ kind: 'Dashboard', label: 'Dashboard', to: `/analytics/dashboards/${e.dashboardId}` })
    return {
      id: m.id,
      kind: 'viz',
      name: e.title ?? '',
      vizType: e.visualizationType ?? 'TABLE',
      configuration: e.configuration ?? {},
      data: e.data ?? [],
      sourceQuery: typeof e.sourceQuery === 'string' && e.sourceQuery.trim() ? e.sourceQuery : undefined,
      savedQueryId: e.savedQueryId,
      visualizationId: e.visualizationId,
      artifacts,
      investigation: Array.isArray(e.investigation) ? e.investigation : [],
    }
  }
  // A text turn.
  const event = parseEvent(m.event)
  if (!event?.parts) return null
  const text = event.parts
    .filter((p: EventPart) => p.text && p.text.trim().length > 0 && !p.thought && typeof p.functionCall === 'undefined' && typeof p.functionResponse === 'undefined')
    .map((p: EventPart) => p.text)
    .join('\n')
  if (!text.trim()) return null
  return {
    id: m.id,
    kind: 'text',
    role: event.type === 'ai.koog.prompt.message.Message.Assistant' ? 'assistant' : 'user',
    text,
  }
}

// A message handed off from the landing page (?message=…). Render the user's
// turn immediately so the new conversation feels continuous with what they just
// typed, instead of flashing an empty "Loading conversation…" state while the
// session and realtime subscriptions warm up. The actual send is deferred to
// onMounted so the subscriptions are ready for Kit's streamed reply.
const handoffRaw = route.query.message
const handoff = (typeof handoffRaw === 'string' ? handoffRaw : '').trim()

const messages = ref<ChatMessage[]>(
  handoff ? [{ id: `optimistic-${Date.now()}`, kind: 'text', role: 'user', text: handoff }] : [],
)
const processing = ref(Boolean(handoff))
const input = ref('')
const sending = ref(false)
const seenIds = new Set<string>()

function syncFromSession(s: ChatSession | null | undefined) {
  if (!s) return
  const parsed = (s.messages ?? [])
    .map(parseMessage)
    .filter(Boolean) as ChatMessage[]
  for (const m of parsed) seenIds.add(m.id)
  // Preserve any optimistic turns the server hasn't echoed back yet so a freshly
  // handed-off message doesn't blink out when the (initially empty) session loads.
  const pending = messages.value.filter(m => m.id.startsWith('optimistic-'))
  messages.value = [...parsed, ...pending]
  // Don't let that initial empty-session load clear the "thinking" state we set
  // optimistically for a handed-off message that hasn't been sent yet.
  if (parsed.length || !pending.length) {
    processing.value = s.processing ?? false
  }
}

watch(session, syncFromSession, { immediate: true })

// ── Subscriptions ───────────────────────────────────────────────────
useSubscription<{ aiChatHistory: ChatSessionMessage }>(
  subscribeHistoryGql,
  { id: sessionId.value },
  (data) => {
    if (data.aiChatHistory) {
      const msg = parseMessage(data.aiChatHistory)
      if (msg && !seenIds.has(msg.id)) {
        seenIds.add(msg.id)
        messages.value = [...messages.value.filter(m => !m.id.startsWith('optimistic-')), msg]
      }
    }
  },
)

useSubscription<{ aiChatProcessing: boolean }>(
  subscribeProcessingGql,
  { id: sessionId.value },
  (data) => {
    if (data.aiChatProcessing !== undefined) {
      processing.value = data.aiChatProcessing
      if (!data.aiChatProcessing) {
        refresh()
      }
    }
  },
)

// Polling fallback — re-fetch session when processing, in case subscriptions miss events
let pollTimer: ReturnType<typeof setInterval> | undefined

watch(processing, (val) => {
  if (val && !pollTimer) {
    pollTimer = setInterval(() => refresh(), 3000)
  } else if (!val && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
})

async function sendMessage(text: string, { showOptimistic = true }: { showOptimistic?: boolean } = {}) {
  if (!text.trim()) return
  sending.value = true
  processing.value = true

  // Optimistic user message — skipped when the bubble was already seeded from a
  // landing-page handoff, so we don't render it twice.
  const optimisticId = showOptimistic ? `optimistic-${Date.now()}` : null
  if (optimisticId) {
    messages.value = [...messages.value, { id: optimisticId, kind: 'text', role: 'user', text }]
  }

  try {
    await gqlMutation(sendGql, {
      id: sessionId.value,
      message: { role: 'user', parts: [{ type: 'text', text }] },
    })
  } catch {
    processing.value = false
    messages.value = optimisticId
      ? messages.value.filter(m => m.id !== optimisticId)
      : messages.value.filter(m => !m.id.startsWith('optimistic-'))
    toast.error('Failed to send message')
  } finally {
    sending.value = false
  }
}

function onSubmit() {
  const text = input.value
  input.value = ''
  sendMessage(text)
}

function resultName(message: ChatVizMessage): string {
  return message.name.trim() || 'Analytics result'
}

function relatedAnalyticsResults(message: ChatVizMessage): ChatVizMessage[] {
  const sourceQuery = message.sourceQuery?.trim()
  return messages.value.filter((candidate): candidate is ChatVizMessage =>
    candidate.kind === 'viz'
    && (candidate.id === message.id || Boolean(sourceQuery && candidate.sourceQuery?.trim() === sourceQuery)),
  )
}

function artifactsForResult(message: ChatVizMessage): AnalyticsArtifactLink[] {
  const artifacts = relatedAnalyticsResults(message).flatMap(result => result.artifacts)
  return [...new Map(artifacts.map(artifact => [artifact.to, artifact])).values()]
}

function savedQueryIdForResult(message: ChatVizMessage): string | undefined {
  return relatedAnalyticsResults(message).find(result => result.savedQueryId)?.savedQueryId
}

function visualizationIdForResult(message: ChatVizMessage): string | undefined {
  return relatedAnalyticsResults(message).find(result => result.visualizationId)?.visualizationId
}

function saveAnalyticsQuery(message: ChatVizMessage) {
  if (!message.sourceQuery?.trim() || savedQueryIdForResult(message) || sending.value || processing.value) return
  void sendMessage([
    `Save the query from the "${resultName(message)}" analytics result as a durable saved query.`,
    'Use this exact SQL:',
    message.sourceQuery,
  ].join('\n\n'))
}

function saveAnalyticsVisualization(message: ChatVizMessage) {
  if (visualizationIdForResult(message) || sending.value || processing.value) return

  const savedQueryId = savedQueryIdForResult(message)
  const backingQuery = savedQueryId
    ? `Use saved query ${savedQueryId} as its backing query.`
    : message.sourceQuery?.trim()
      ? `First save its backing query using this exact SQL:\n\n${message.sourceQuery}`
      : ''
  if (!backingQuery) return

  void sendMessage([
    `Save the "${resultName(message)}" analytics result as a durable ${message.vizType.toUpperCase()} visualization.`,
    backingQuery,
    `Preserve this visualization configuration:\n${JSON.stringify(message.configuration, null, 2)}`,
  ].join('\n\n'))
}

const messagesEl = ref<HTMLElement>()
watch(messages, () => {
  nextTick(() => {
    if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight
  })
}, { deep: true })

onMounted(async () => {
  if (!handoff) return
  // Drop ?message= from the URL so a refresh doesn't resend it.
  const query = { ...route.query }
  delete query.message
  await router.replace({ query })
  // The user's turn is already on screen (seeded above); give the realtime
  // subscriptions a moment to connect before sending so Kit's streamed reply
  // isn't missed. The send reuses the seeded bubble rather than adding another.
  setTimeout(() => sendMessage(handoff, { showOptimistic: false }), 500)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('AI', 'Kit', title)"
        :title="title"
      >
        <template #actions>
          <Button
            size="sm"
            icon="plus"
            :accent="accent"
            @click="router.push('/ai/chat')">New Chat</Button>
          <Button size="sm" icon="list" @click="router.push('/ai/history')">History</Button>
        </template>
      </PageHeader>
    </template>

    <div class="chat-layout">
      <div ref="messagesEl" class="chat-messages">
        <div v-if="!messages.length && queryStatus === 'pending'" class="chat-loading">
          <Icon name="wand" :size="24" color="var(--fg-4)" />
          <span>Loading conversation…</span>
        </div>
        <div v-else-if="!messages.length && !processing" class="chat-loading">
          <Icon name="wand" :size="24" :color="accent" />
          <span>Start a conversation with Kit</span>
        </div>
        <TransitionGroup
          name="msg"
          tag="div"
          class="chat-thread"
          appear>
          <div
            v-for="msg in messages"
            :key="msg.id"
            class="chat-bubble"
            :class="msg.kind === 'text' ? msg.role : 'assistant chat-bubble-viz'"
          >
            <div class="chat-bubble-header">
              <span class="chat-bubble-role">{{ msg.kind === 'text' && msg.role === 'user' ? 'You' : 'Kit' }}</span>
            </div>
            <div v-if="msg.kind === 'text'" class="chat-bubble-text">
              <KitMessageContent v-if="msg.role === 'assistant'" :text="msg.text" />
              <template v-else>{{ msg.text }}</template>
            </div>
            <template v-else>
              <KitAnalyticsResult
                :name="msg.name"
                :visualization-type="msg.vizType"
                :configuration="msg.configuration"
                :data="msg.data"
                :source-query="msg.sourceQuery"
                :artifacts="artifactsForResult(msg)"
                :investigation="msg.investigation"
                :busy="sending || processing"
                @save-query="saveAnalyticsQuery(msg)"
                @save-visualization="saveAnalyticsVisualization(msg)"
              />
            </template>
          </div>
        </TransitionGroup>
        <div v-if="processing" class="chat-processing">
          <span class="chat-processing-dot" />
          <span>Kit is thinking…</span>
        </div>
      </div>

      <div class="chat-input-area">
        <div class="chat-input-wrap">
          <textarea
            v-model="input"
            class="chat-textarea"
            placeholder="Message Kit…"
            rows="2"
            :disabled="sending"
            @keydown.enter.exact.prevent="onSubmit"
          />
          <div class="chat-input-footer">
            <Button
              primary
              size="sm"
              icon="arrowRight"
              :accent="accent"
              :disabled="sending || !input.trim()"
              @click="onSubmit">
              Send
            </Button>
          </div>
        </div>
      </div>
    </div>
  </PageShell>
</template>

<style scoped>
:deep(.page-content) {
  padding: 0 22px 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-layout {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  max-width: 800px;
  margin: 0 auto;
  width: 100%;
}

.chat-messages {
  flex: 1;
  overflow: auto;
  padding: 20px 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* The transitioning message list — carries the column layout the bubbles align within. */
.chat-thread {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* Messages animate in — fade + a small rise — as the conversation loads and as new turns arrive. */
.msg-enter-active,
.msg-appear-active {
  transition: opacity 0.32s ease, transform 0.32s cubic-bezier(0.22, 1, 0.36, 1);
}

.msg-enter-from,
.msg-appear-from {
  opacity: 0;
  transform: translateY(10px);
}

/* Smoothly slide existing bubbles when the list reorders (e.g. optimistic → confirmed message). */
.msg-move {
  transition: transform 0.32s cubic-bezier(0.22, 1, 0.36, 1);
}

@media (prefers-reduced-motion: reduce) {
  .msg-enter-active,
  .msg-appear-active,
  .msg-move {
    transition: none;
  }

  .msg-enter-from,
  .msg-appear-from {
    opacity: 1;
    transform: none;
  }
}

.chat-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  flex: 1;
  color: var(--fg-3);
  font-size: 13px;
}

.chat-bubble {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  border-radius: var(--r-md);
  max-width: 85%;
}

.chat-bubble.user {
  align-self: flex-end;
  background: color-mix(in oklch, var(--fg-2) 10%, transparent);
}

.chat-bubble.assistant {
  align-self: flex-start;
  background: var(--bg-1);
  border: 1px solid var(--line);
}

/* A visualization turn needs more room than a text bubble. */
.chat-bubble-viz {
  max-width: 560px;
  width: 100%;
}

.chat-bubble-header {
  display: flex;
  align-items: center;
  gap: 6px;
}

.chat-bubble-role {
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.chat-bubble-text {
  font-size: 14px;
  color: var(--fg-0);
  line-height: 1.55;
  white-space: pre-wrap;
}

.chat-processing {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--fg-2);
  padding: 8px 16px;
}

.chat-processing-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: v-bind(accent);
  animation: pulse-dot 1.2s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%, 100% { opacity: 0.3; transform: scale(0.85); }
  50% { opacity: 1; transform: scale(1); }
}

.chat-input-area {
  flex-shrink: 0;
  padding: 16px 0;
  border-top: 1px solid var(--line);
}

.chat-input-wrap {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-1);
  overflow: hidden;
}

.chat-textarea {
  width: 100%;
  padding: 12px 14px;
  font-size: 14px;
  color: var(--fg-0);
  background: transparent;
  border: none;
  resize: none;
  font-family: inherit;
  line-height: 1.5;
}

.chat-textarea::placeholder { color: var(--fg-3); }
.chat-textarea:focus { outline: none; }

.chat-input-footer {
  display: flex;
  justify-content: flex-end;
  padding: 8px 12px;
  border-top: 1px solid color-mix(in oklch, var(--line) 50%, transparent);
}
</style>
