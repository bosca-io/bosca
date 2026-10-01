<script lang="ts" setup>
import gql from 'graphql-tag'

type BridgePlatform = 'SLACK' | 'TEAMS'

interface BridgeBinding {
  id: string
  platform: BridgePlatform
  externalChannelId: string
  workspaceId: string
  active: boolean
  createdAt: string | null
}

interface BridgeBindingsResult {
  collaboration: {
    bridge: {
      bindings: BridgeBinding[]
    }
  }
}

const props = defineProps<{
  channelId: string
}>()

const { query, mutation } = useGraphQL()
const toast = useToast()

const bindings = ref<BridgeBinding[]>([])
const loading = ref(true)
const loadError = ref('')
const showCreate = ref(false)
const newPlatform = ref<BridgePlatform>('SLACK')
const newExternalChannelId = ref('')
const newWorkspaceId = ref('')
const newBotToken = ref('')
const creating = ref(false)
const tokenTarget = ref<BridgeBinding | null>(null)
const replacementBotToken = ref('')
const savingToken = ref(false)
const deactivateTarget = ref<BridgeBinding | null>(null)
const deactivating = ref(false)

const platformOptions = [
  { value: 'SLACK', label: 'Slack' },
  { value: 'TEAMS', label: 'Microsoft Teams (not available)', disabled: true },
]

const GET_BINDINGS = gql`
  query GetBridgeBindings($channelId: UUID!) {
    collaboration {
      bridge {
        bindings(channelId: $channelId) {
          id
          platform
          externalChannelId
          workspaceId
          active
          createdAt
        }
      }
    }
  }
`

const CREATE_BINDING = gql`
  mutation CreateBridgeBinding($input: CreateBridgeBindingInput!) {
    collaboration {
      bridge {
        createBinding(input: $input) {
          id
        }
      }
    }
  }
`

const SET_BOT_TOKEN = gql`
  mutation SetBridgeBotToken($id: UUID!, $token: String) {
    collaboration {
      bridge {
        setBotToken(id: $id, token: $token)
      }
    }
  }
`

const DEACTIVATE_BINDING = gql`
  mutation DeactivateBridgeBinding($id: UUID!) {
    collaboration {
      bridge {
        deactivateBinding(id: $id)
      }
    }
  }
`

const canCreate = computed(() =>
  !creating.value
  && newPlatform.value === 'SLACK'
  && newExternalChannelId.value.trim().length > 0
  && newWorkspaceId.value.trim().length > 0
  && newBotToken.value.trim().length > 0,
)

const canSaveToken = computed(() =>
  !savingToken.value && replacementBotToken.value.trim().length > 0,
)

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

function platformLabel(platform: BridgePlatform): string {
  return platform === 'SLACK' ? 'Slack' : 'Microsoft Teams'
}

function formatCreatedAt(createdAt: string | null): string {
  if (!createdAt) return ''
  const date = new Date(createdAt)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

async function loadBindings() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await query<BridgeBindingsResult>(GET_BINDINGS, { channelId: props.channelId })
    bindings.value = result.collaboration.bridge.bindings
  } catch (error: unknown) {
    bindings.value = []
    loadError.value = errorMessage(error, 'Failed to load bridges')
    toast.error(loadError.value)
  } finally {
    loading.value = false
  }
}

function resetCreateForm() {
  newPlatform.value = 'SLACK'
  newExternalChannelId.value = ''
  newWorkspaceId.value = ''
  newBotToken.value = ''
}

function closeCreate() {
  if (creating.value) return
  showCreate.value = false
  resetCreateForm()
}

async function onCreate() {
  if (!canCreate.value) return
  creating.value = true
  try {
    await mutation(CREATE_BINDING, {
      input: {
        channelId: props.channelId,
        platform: newPlatform.value,
        externalChannelId: newExternalChannelId.value.trim(),
        workspaceId: newWorkspaceId.value.trim(),
        botToken: newBotToken.value.trim(),
      },
    })
    toast.success('Slack bridge created')
    showCreate.value = false
    resetCreateForm()
    await loadBindings()
  } catch (error: unknown) {
    toast.error(errorMessage(error, 'Failed to create bridge'))
  } finally {
    creating.value = false
  }
}

function openTokenEditor(binding: BridgeBinding) {
  tokenTarget.value = binding
  replacementBotToken.value = ''
}

function closeTokenEditor() {
  if (savingToken.value) return
  tokenTarget.value = null
  replacementBotToken.value = ''
}

async function onSaveToken() {
  if (!tokenTarget.value || !canSaveToken.value) return
  savingToken.value = true
  try {
    await mutation(SET_BOT_TOKEN, {
      id: tokenTarget.value.id,
      token: replacementBotToken.value.trim(),
    })
    toast.success('Slack bot token updated')
    tokenTarget.value = null
    replacementBotToken.value = ''
  } catch (error: unknown) {
    toast.error(errorMessage(error, 'Failed to update bot token'))
  } finally {
    savingToken.value = false
  }
}

async function onDeactivate() {
  if (!deactivateTarget.value || deactivating.value) return
  deactivating.value = true
  try {
    await mutation(DEACTIVATE_BINDING, { id: deactivateTarget.value.id })
    toast.success(`${platformLabel(deactivateTarget.value.platform)} bridge deactivated`)
    deactivateTarget.value = null
    await loadBindings()
  } catch (error: unknown) {
    toast.error(errorMessage(error, 'Failed to deactivate bridge'))
  } finally {
    deactivating.value = false
  }
}

onMounted(loadBindings)
</script>

<template>
  <section class="bridge-admin">
    <div class="bridge-header">
      <div>
        <div class="bridge-title">Messaging bridges</div>
        <p class="bridge-description">Connect this Bosca channel to a Slack channel.</p>
      </div>
      <Button
        v-if="!showCreate"
        size="xs"
        icon="plus"
        @click="showCreate = true"
      >
        Add Slack
      </Button>
    </div>

    <div v-if="showCreate" class="bridge-create">
      <Select
        v-model="newPlatform"
        label="Platform"
        :options="platformOptions"
        size="sm"
      />
      <TextInput
        v-model="newWorkspaceId"
        label="Slack workspace ID"
        placeholder="T01234567"
        size="sm"
        mono
      />
      <TextInput
        v-model="newExternalChannelId"
        label="Slack channel ID"
        placeholder="C01234567"
        size="sm"
        mono
      />
      <TextInput
        v-model="newBotToken"
        label="Bot token"
        placeholder="xoxb-…"
        type="password"
        size="sm"
        mono
      />
      <p class="bridge-help">The bot token is stored encrypted and is never returned to Studio.</p>
      <div class="bridge-create-actions">
        <Button
          size="xs"
          :disabled="creating"
          @click="closeCreate"
        >
          Cancel
        </Button>
        <Button
          primary
          size="xs"
          :disabled="!canCreate"
          @click="onCreate"
        >
          {{ creating ? 'Creating…' : 'Create Bridge' }}
        </Button>
      </div>
    </div>

    <div v-if="loading" class="bridge-message">Loading bridges…</div>
    <div v-else-if="loadError" class="bridge-message bridge-message--error">
      <span>{{ loadError }}</span>
      <Button size="xs" @click="loadBindings">Retry</Button>
    </div>
    <div v-else-if="bindings.length === 0 && !showCreate" class="bridge-message">No bridges configured.</div>
    <div v-else-if="bindings.length > 0" class="bridge-list">
      <article v-for="binding in bindings" :key="binding.id" class="bridge-row">
        <div class="bridge-row-header">
          <span class="bridge-platform">{{ platformLabel(binding.platform) }}</span>
          <span class="bridge-status" :class="{ 'bridge-status--inactive': !binding.active }">
            {{ binding.active ? 'Active' : 'Inactive' }}
          </span>
        </div>
        <dl class="bridge-details">
          <div>
            <dt>Workspace</dt>
            <dd>{{ binding.workspaceId }}</dd>
          </div>
          <div>
            <dt>Channel</dt>
            <dd>{{ binding.externalChannelId }}</dd>
          </div>
        </dl>
        <div class="bridge-row-footer">
          <span v-if="formatCreatedAt(binding.createdAt)" class="bridge-created">
            Added {{ formatCreatedAt(binding.createdAt) }}
          </span>
          <span class="bridge-row-actions">
            <Button
              v-if="binding.platform === 'SLACK' && binding.active"
              size="xs"
              icon="key"
              @click="openTokenEditor(binding)"
            >
              Update token
            </Button>
            <Button
              v-if="binding.active"
              size="xs"
              @click="deactivateTarget = binding"
            >
              Deactivate
            </Button>
          </span>
        </div>
      </article>
    </div>

    <Modal
      v-if="tokenTarget"
      title="Update Slack bot token"
      subtitle="Replace the encrypted credential used for outbound Slack messages"
      icon="key"
      width="440px"
      @close="closeTokenEditor"
    >
      <TextInput
        v-model="replacementBotToken"
        label="New bot token"
        placeholder="xoxb-…"
        type="password"
        mono
        autofocus
      />
      <p class="bridge-help">The existing token cannot be viewed. Saving replaces it immediately.</p>
      <template #footer>
        <span class="spacer" />
        <Button
          size="sm"
          :disabled="savingToken"
          @click="closeTokenEditor"
        >
          Cancel
        </Button>
        <Button
          primary
          size="sm"
          :disabled="!canSaveToken"
          @click="onSaveToken"
        >
          {{ savingToken ? 'Saving…' : 'Save Token' }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deactivateTarget"
      title="Deactivate messaging bridge?"
      subtitle="Messages will stop synchronizing with the external channel. The encrypted bot token will be cleared."
      confirm-label="Deactivate"
      :loading="deactivating"
      @close="deactivateTarget = null"
      @confirm="onDeactivate"
    >
      <p class="confirm-copy">
        Deactivate the {{ platformLabel(deactivateTarget.platform) }} bridge for
        <span class="mono">{{ deactivateTarget.externalChannelId }}</span>?
      </p>
    </ConfirmModal>
  </section>
</template>

<style scoped>
.bridge-admin {
  margin-top: 16px;
}

.bridge-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.bridge-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.bridge-description,
.bridge-help {
  margin: 4px 0 0;
  font-size: 11.5px;
  line-height: 1.45;
  color: var(--fg-3);
}

.bridge-create {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 12px;
  padding: 12px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.bridge-create-actions,
.bridge-row-footer,
.bridge-row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bridge-create-actions {
  justify-content: flex-end;
}

.bridge-message {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 0;
  font-size: 12px;
  color: var(--fg-3);
}

.bridge-message--error {
  color: var(--err);
}

.bridge-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bridge-row {
  padding: 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.bridge-row-header,
.bridge-row-footer {
  justify-content: space-between;
}

.bridge-row-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bridge-platform {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg-1);
}

.bridge-status {
  padding: 2px 6px;
  font-size: 10.5px;
  font-weight: 600;
  color: #34d99a;
  background: color-mix(in oklch, #34d99a 12%, transparent);
  border-radius: 999px;
}

.bridge-status--inactive {
  color: var(--fg-3);
  background: var(--bg-3);
}

.bridge-details {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
  margin: 10px 0;
}

.bridge-details div {
  min-width: 0;
}

.bridge-details dt {
  margin-bottom: 2px;
  font-size: 10.5px;
  color: var(--fg-3);
}

.bridge-details dd {
  margin: 0;
  overflow: hidden;
  font-family: var(--font-mono, monospace);
  font-size: 11px;
  color: var(--fg-1);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bridge-created {
  font-size: 10.5px;
  color: var(--fg-3);
}

.spacer {
  flex: 1;
}

.confirm-copy {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--fg-2);
}

.mono {
  font-family: var(--font-mono, monospace);
}
</style>
