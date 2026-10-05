<script setup lang="ts">
import gql from 'graphql-tag'

definePageMeta({
  // Cross-fade with the conversation view so submitting a message eases between
  // the two layouts instead of hard-cutting. See .page-fade-* in main.css.
  pageTransition: { name: 'page-fade', mode: 'out-in' },
})

const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const input = ref('')
const loading = ref(false)

const sessionsGql = gql`
  query GetChatSessions { chatSessions { all { id title modified } } }
`
const createGql = gql`
  mutation CreateChatSession($input: ChatSessionInput!) { chatSessions { create(session: $input) { id } } }
`

const { data } = useAsyncQuery<{ chatSessions: { all: Array<{ id: string; title: string; modified: string }> } }>(
  'kit-sessions', sessionsGql, {}, { server: false },
)
const sessions = computed(() => data.value?.chatSessions?.all ?? [])

async function createChat(prompt: string) {
  if (!prompt.trim()) return
  loading.value = true
  try {
    const result = await gqlMutation<{ chatSessions: { create: { id: string } } }>(createGql, {
      input: { agentKey: 'kit', title: prompt.substring(0, 50) },
    })
    const sessionId = result.chatSessions.create.id
    router.push(`/ai/chat/${sessionId}?message=${encodeURIComponent(prompt)}`)
  } catch {
    toast.error('Failed to create session')
  } finally {
    loading.value = false
  }
}

function onSubmit() {
  createChat(input.value)
  input.value = ''
}

const quickChats = [
  { label: 'Search for documents about Bosca', icon: 'search' },
  { label: 'List all of my collections', icon: 'folder' },
  { label: 'What content templates are available?', icon: 'wand' },
  { label: 'Help me create a new collection', icon: 'plus' },
  { label: 'Find all documents in the DRAFT state', icon: 'file' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('AI', 'Kit')"
        title="Kit"
        subtitle="AI Assistant"
      >
        <template #actions>
          <Button
            size="sm"
            icon="list"
            :accent="accent"
            @click="router.push('/ai/history')">History</Button>
        </template>
      </PageHeader>
    </template>

    <div class="kit-landing">
      <div class="kit-hero">
        <div class="kit-avatar" :style="{ background: `color-mix(in oklch, ${accent} 16%, transparent)`, border: `1px solid color-mix(in oklch, ${accent} 30%, transparent)` }">
          <Icon name="wand" :size="28" :color="accent" />
        </div>
        <h2 class="kit-title">Hello, I'm Kit.</h2>
        <p class="kit-subtitle">How can I help you today?</p>
      </div>

      <div class="kit-input-area">
        <div class="kit-input-wrap">
          <textarea
            v-model="input"
            class="kit-textarea"
            placeholder="Ask Kit anything…"
            rows="3"
            :disabled="loading"
            @keydown.enter.exact.prevent="onSubmit"
          />
          <div class="kit-input-footer">
            <Button
              primary
              size="sm"
              icon="arrowRight"
              :accent="accent"
              :disabled="loading || !input.trim()"
              @click="onSubmit">
              {{ loading ? 'Creating…' : 'Send' }}
            </Button>
          </div>
        </div>
      </div>

      <div class="kit-quick">
        <button
          v-for="q in quickChats"
          :key="q.label"
          class="kit-quick-btn"
          :disabled="loading"
          @click="createChat(q.label)"
        >
          <Icon :name="q.icon" :size="13" color="var(--fg-3)" />
          <span>{{ q.label }}</span>
        </button>
      </div>

      <div v-if="sessions.length" class="kit-recent">
        <div class="kit-recent-title">Recent sessions</div>
        <div class="kit-recent-list">
          <button
            v-for="s in sessions.slice(0, 5)"
            :key="s.id"
            class="kit-recent-item"
            @click="router.push(`/ai/chat/${s.id}`)"
          >
            <Icon name="message" :size="12" color="var(--fg-3)" />
            <span class="kit-recent-label">{{ s.title }}</span>
            <Icon name="chevron-right" :size="10" color="var(--fg-4)" />
          </button>
        </div>
      </div>
    </div>
  </PageShell>
</template>

<style scoped>
.kit-landing {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 32px;
  padding: 48px 20px;
  max-width: 640px;
  margin: 0 auto;
}

.kit-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  text-align: center;
}

.kit-avatar {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.kit-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--fg-0);
  letter-spacing: -0.02em;
  margin: 0;
}

.kit-subtitle {
  font-size: 14px;
  color: var(--fg-2);
  margin: 0;
}

.kit-input-area {
  width: 100%;
}

.kit-input-wrap {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--bg-1);
  overflow: hidden;
}

.kit-textarea {
  width: 100%;
  padding: 14px 16px;
  font-size: 14px;
  color: var(--fg-0);
  background: transparent;
  border: none;
  resize: none;
  font-family: inherit;
  line-height: 1.5;
}

.kit-textarea::placeholder {
  color: var(--fg-3);
}

.kit-textarea:focus {
  outline: none;
}

.kit-input-footer {
  display: flex;
  justify-content: flex-end;
  padding: 8px 12px;
  border-top: 1px solid color-mix(in oklch, var(--line) 50%, transparent);
}

.kit-quick {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: center;
}

.kit-quick-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: 100px;
  font-size: 12.5px;
  color: var(--fg-2);
  border: 1px solid var(--line);
  background: var(--bg-1);
  transition: background 0.15s, border-color 0.15s;
}

.kit-quick-btn:hover {
  background: var(--bg-2);
  border-color: var(--fg-3);
}

.kit-recent {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kit-recent-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding-left: 4px;
}

.kit-recent-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.kit-recent-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  font-size: 13px;
  color: var(--fg-1);
  transition: background 0.15s;
}

.kit-recent-item:hover {
  background: color-mix(in oklch, var(--fg-3) 8%, transparent);
}

.kit-recent-label {
  flex: 1;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
