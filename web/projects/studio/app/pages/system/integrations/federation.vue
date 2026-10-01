<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface FederationPeer {
  id: string
  name: string
  natsUrl: string
  apiUrl: string
  createdAt: string | null
}

const peersGql = gql`
  query GetStudioFederationPeers {
    collaboration {
      federation {
        peers {
          id
          name
          natsUrl
          apiUrl
          createdAt
        }
      }
    }
  }
`

const registerPeerGql = gql`
  mutation RegisterStudioFederationPeer($input: RegisterFederationPeerInput!) {
    collaboration {
      federation {
        registerPeer(input: $input) { id }
      }
    }
  }
`

const deactivatePeerGql = gql`
  mutation DeactivateStudioFederationPeer($id: UUID!) {
    collaboration {
      federation {
        deactivatePeer(id: $id)
      }
    }
  }
`

const setSharedSecretGql = gql`
  mutation SetStudioFederationSharedSecret($id: UUID!, $secret: String) {
    collaboration {
      federation {
        setSharedSecret(id: $id, secret: $secret)
      }
    }
  }
`

const { data, status, refresh } = useAsyncQuery<{
  collaboration: { federation: { peers: FederationPeer[] } }
}>('studio-federation-peers', peersGql, {}, { server: false })

const peers = computed(() => data.value?.collaboration?.federation?.peers ?? [])

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Peer', width: 'minmax(160px, 1.2fr)' },
  { key: 'natsUrl', label: 'NATS URL', width: 'minmax(180px, 1.5fr)', muted: true },
  { key: 'apiUrl', label: 'API URL', width: 'minmax(180px, 1.5fr)', muted: true },
  { key: 'createdAt', label: 'Registered', width: '130px', muted: true },
]

const showRegister = ref(false)
const newName = ref('')
const newNatsUrl = ref('')
const newApiUrl = ref('')
const newSecret = ref('')
const registering = ref(false)

const secretTarget = ref<FederationPeer | null>(null)
const nextSecret = ref('')
const updatingSecret = ref(false)

const deactivateTarget = ref<FederationPeer | null>(null)
const deactivating = ref(false)

function absoluteUrlError(value: string, protocols?: string[]): string {
  const input = value.trim()
  if (!input) return ''
  try {
    const url = new URL(input)
    if (!url.host || (protocols && !protocols.includes(url.protocol))) {
      return protocols ? 'URL must use HTTP or HTTPS.' : 'URL must include a protocol and host.'
    }
    return ''
  } catch {
    return 'Enter an absolute URL.'
  }
}

const natsUrlError = computed(() => absoluteUrlError(newNatsUrl.value))
const apiUrlError = computed(() => absoluteUrlError(newApiUrl.value, ['http:', 'https:']))
const canRegister = computed(() =>
  !registering.value
  && !!newName.value.trim()
  && !!newNatsUrl.value.trim()
  && !!newApiUrl.value.trim()
  && !!newSecret.value.trim()
  && !natsUrlError.value
  && !apiUrlError.value)

function resetRegistration() {
  showRegister.value = false
  newName.value = ''
  newNatsUrl.value = ''
  newApiUrl.value = ''
  newSecret.value = ''
}

async function registerPeer() {
  if (!canRegister.value) return
  registering.value = true
  try {
    await mutation(registerPeerGql, {
      input: {
        name: newName.value.trim(),
        natsUrl: newNatsUrl.value.trim(),
        apiUrl: newApiUrl.value.trim(),
        sharedSecret: newSecret.value.trim(),
      },
    })
    resetRegistration()
    toast.success('Federation peer registered')
    await refresh()
  } catch (error: unknown) {
    toast.error(`Failed to register peer: ${error instanceof Error ? error.message : String(error)}`)
  } finally {
    registering.value = false
  }
}

function openSecretEditor(peer: FederationPeer) {
  secretTarget.value = peer
  nextSecret.value = ''
}

async function updateSharedSecret() {
  if (!secretTarget.value || updatingSecret.value) return
  updatingSecret.value = true
  try {
    await mutation(setSharedSecretGql, {
      id: secretTarget.value.id,
      secret: nextSecret.value.trim() || null,
    })
    secretTarget.value = null
    nextSecret.value = ''
    toast.success('Federation shared secret updated')
  } catch (error: unknown) {
    toast.error(`Failed to update shared secret: ${error instanceof Error ? error.message : String(error)}`)
  } finally {
    updatingSecret.value = false
  }
}

async function deactivatePeer() {
  if (!deactivateTarget.value || deactivating.value) return
  deactivating.value = true
  try {
    await mutation(deactivatePeerGql, { id: deactivateTarget.value.id })
    deactivateTarget.value = null
    toast.success('Federation peer deactivated')
    await refresh()
  } catch (error: unknown) {
    toast.error(`Failed to deactivate peer: ${error instanceof Error ? error.message : String(error)}`)
  } finally {
    deactivating.value = false
  }
}

function getRowActions(): OverflowMenuItem[] {
  return [
    { id: 'secret', label: 'Update shared secret', icon: 'key' },
    { id: 'separator', label: '', separator: true },
    { id: 'deactivate', label: 'Deactivate', icon: 'pause', danger: true },
  ]
}

function onRowAction(action: string, peer: FederationPeer) {
  if (action === 'secret') openSecretEditor(peer)
  else if (action === 'deactivate') deactivateTarget.value = peer
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Integrations', 'Federation')"
        title="Federation"
        :subtitle="`${peers.length} active ${peers.length === 1 ? 'peer' : 'peers'}`"
      >
        <template #actions>
          <Button size="sm" icon="refresh" @click="refresh()">Refresh</Button>
          <Button
            size="sm"
            primary
            :accent="accent"
            icon="plus"
            @click="showRegister = true"
          >
            Register Peer
          </Button>
        </template>
      </PageHeader>
    </template>

    <div class="federation-stack">
      <SectionCard
        title="Federation Peers"
        subtitle="Remote Bosca instances participating in cross-instance chat"
      >
        <div class="peer-intro">
          Messages are relayed through NATS leaf nodes. The API URL is used for handshake and
          control-plane requests, and shared secrets are stored in encrypted configuration.
        </div>
        <GlassTable
          :columns="columns"
          :rows="peers"
          :loading="status === 'pending' && peers.length === 0"
          loading-text="Loading federation peers…"
          empty-text="No federation peers registered."
          :row-actions="getRowActions"
          @row-action="({ action, row }) => onRowAction(action, row as FederationPeer)"
        >
          <template #col-name="{ row }">
            <span class="peer-name">{{ (row as FederationPeer).name }}</span>
          </template>
          <template #col-createdAt="{ row }">
            {{ formatDate((row as FederationPeer).createdAt) }}
          </template>
        </GlassTable>
      </SectionCard>
    </div>

    <Modal
      v-if="showRegister"
      title="Register Federation Peer"
      icon="link"
      :accent="accent"
      @close="resetRegistration"
    >
      <div class="form-stack">
        <TextInput
          v-model="newName"
          label="Name"
          placeholder="Bosca Production"
          autofocus
        />
        <TextInput
          v-model="newNatsUrl"
          label="NATS URL"
          placeholder="nats://peer.example.org:4222"
        />
        <p :class="natsUrlError ? 'field-error' : 'field-help'">
          {{ natsUrlError || 'Leaf-node URL used to relay messages to this peer.' }}
        </p>
        <TextInput
          v-model="newApiUrl"
          type="url"
          label="API URL"
          placeholder="https://peer.example.org"
        />
        <p :class="apiUrlError ? 'field-error' : 'field-help'">
          {{ apiUrlError || 'Base URL of the peer’s federation REST API.' }}
        </p>
        <TextInput
          v-model="newSecret"
          type="password"
          label="Shared Secret"
          placeholder="Enter a shared secret"
          autocomplete="off"
        />
        <p class="field-help">Used to authenticate peer handshakes and stored encrypted.</p>
      </div>
      <template #footer>
        <span class="footer-spacer" />
        <Button size="sm" @click="resetRegistration">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!canRegister"
          @click="registerPeer"
        >
          {{ registering ? 'Registering…' : 'Register' }}
        </Button>
      </template>
    </Modal>

    <Modal
      v-if="secretTarget"
      :title="`Update Secret for ${secretTarget.name}`"
      icon="key"
      :accent="accent"
      @close="secretTarget = null"
    >
      <div class="form-stack">
        <TextInput
          v-model="nextSecret"
          type="password"
          label="Shared Secret"
          placeholder="Enter a new shared secret"
          autocomplete="off"
          autofocus
        />
        <p class="field-help">
          Enter the secret configured on both peers. Leave it blank to clear the stored secret.
        </p>
      </div>
      <template #footer>
        <span class="footer-spacer" />
        <Button size="sm" @click="secretTarget = null">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="updatingSecret"
          @click="updateSharedSecret"
        >
          {{ updatingSecret ? 'Saving…' : 'Save' }}
        </Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="deactivateTarget"
      :title="`Deactivate ${deactivateTarget.name}?`"
      subtitle="Channel federation links to this peer will stop synchronizing."
      confirm-label="Deactivate"
      :loading="deactivating"
      @close="deactivateTarget = null"
      @confirm="deactivatePeer"
    />
  </PageShell>
</template>

<style scoped>
.federation-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.peer-intro {
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  color: var(--fg-3);
  font-size: 12px;
  line-height: 1.5;
}

.peer-name {
  color: var(--fg-0);
  font-weight: 550;
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.field-help,
.field-error {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
}

.field-help {
  color: var(--fg-3);
}

.field-error {
  color: var(--err);
}

.footer-spacer {
  flex: 1;
}
</style>
