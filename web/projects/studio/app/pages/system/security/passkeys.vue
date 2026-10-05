<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const { $auth } = useNuxtApp()

const passkeysGql = gql`
  query GetMyPasskeys {
    security {
      passkeys {
        current {
          credentialId
          name
          createdAt
          lastUsedAt
          transports
        }
      }
    }
  }
`

const deletePasskeyGql = gql`
  mutation DeletePasskey($credentialId: String!) {
    security {
      passkeys {
        delete(credentialId: $credentialId)
      }
    }
  }
`

interface Passkey {
  credentialId: string
  name: string
  createdAt: string
  lastUsedAt: string | null
  transports: string[]
}

const { data, status, refresh } = useAsyncQuery<{
  security: { passkeys: { current: Passkey[] } }
}>('my-passkeys', passkeysGql)

const passkeys = computed(() => data.value?.security?.passkeys?.current ?? [])
const isLoading = computed(() => status.value === 'pending')

// --- Register ---
const showRegister = ref(false)
const newName = ref('')
const registering = ref(false)

function openRegister() {
  newName.value = ''
  showRegister.value = true
}

async function registerPasskey() {
  if (!newName.value.trim()) {
    toast.error('Name is required')
    return
  }
  registering.value = true
  try {
    await ($auth as unknown as { registerPasskey: (_name: string) => Promise<void> }).registerPasskey(newName.value.trim())
    toast.success('Passkey registered successfully')
    showRegister.value = false
    newName.value = ''
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to register passkey')
  } finally {
    registering.value = false
  }
}

// --- Delete ---
const deleteTarget = ref<Passkey | null>(null)
const deleting = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await gqlMutation(deletePasskeyGql, { credentialId: deleteTarget.value.credentialId })
    toast.success('Passkey removed')
    deleteTarget.value = null
    refresh()
  } catch {
    toast.error('Failed to delete passkey')
  } finally {
    deleting.value = false
  }
}

function formatDate(d: string | null): string {
  if (!d) return 'Never'
  return new Date(d).toLocaleString(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function transportLabel(transport: string): string {
  switch (transport) {
    case 'internal': return 'Platform'
    case 'usb': return 'USB'
    case 'ble': return 'Bluetooth'
    case 'nfc': return 'NFC'
    case 'hybrid': return 'Hybrid'
    default: return transport
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Security', 'Passkeys')"
        title="Passkeys"
        :subtitle="`${passkeys.length} registered passkey${passkeys.length === 1 ? '' : 's'}`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openRegister">Register Passkey</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Registered Passkeys">
      <div v-if="isLoading && passkeys.length === 0" class="empty-state">
        <span>Loading…</span>
      </div>

      <div v-else-if="passkeys.length === 0" class="empty-state">
        <Icon name="passkey" :size="40" color="var(--fg-4)" />
        <span class="empty-title">No passkeys registered</span>
        <span class="empty-desc">Add a passkey for passwordless sign-in using your device's biometrics or security key.</span>
        <Button
          size="sm"
          icon="plus"
          :accent="accent"
          @click="openRegister">Register Passkey</Button>
      </div>

      <div v-else>
        <div v-for="pk in passkeys" :key="pk.credentialId" class="passkey-row">
          <Icon name="passkey" :size="24" color="var(--fg-2)" />
          <div class="passkey-info">
            <div class="passkey-name">{{ pk.name }}</div>
            <div class="passkey-meta">
              Created {{ formatDate(pk.createdAt) }} · Last used {{ formatDate(pk.lastUsedAt) }}
            </div>
            <div v-if="pk.transports.length > 0" class="passkey-transports">
              <Badge v-for="t in pk.transports" :key="t" color="var(--fg-3)">{{ transportLabel(t) }}</Badge>
            </div>
          </div>
          <Button
            size="sm"
            icon="trash"
            primary
            accent="var(--err)"
            @click="deleteTarget = pk">Remove</Button>
        </div>
      </div>
    </SectionCard>

    <!-- Register Modal -->
    <Modal
      v-if="showRegister"
      title="Register Passkey"
      icon="passkey"
      :accent="accent"
      @close="showRegister = false">
      <div class="register-form">
        <p class="register-desc">Give this passkey a name to identify it later (e.g., "MacBook Pro" or "Security Key").</p>
        <TextInput
          v-model="newName"
          label="Name"
          placeholder="e.g. MacBook Pro Touch ID"
          autofocus
          @keyup.enter="registerPasskey"
        />
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showRegister = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newName.trim() || registering"
          @click="registerPasskey">
          {{ registering ? 'Registering…' : 'Register' }}
        </Button>
      </template>
    </Modal>

    <!-- Delete Confirmation -->
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Remove '${deleteTarget.name}'?`"
      subtitle="You will no longer be able to sign in with this passkey. This cannot be undone."
      confirm-label="Remove"
      :loading="deleting"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 64px 32px;
  text-align: center;
}

.empty-title {
  font-size: 14px;
  font-weight: 550;
  color: var(--fg-1);
}

.empty-desc {
  font-size: 12px;
  color: var(--fg-3);
  max-width: 340px;
}

.passkey-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}

.passkey-row:last-child {
  border-bottom: none;
}

.passkey-info {
  flex: 1;
  min-width: 0;
}

.passkey-name {
  font-size: 13px;
  font-weight: 550;
  color: var(--fg-0);
}

.passkey-meta {
  font-size: 11px;
  color: var(--fg-3);
  margin-top: 2px;
}

.passkey-transports {
  display: flex;
  gap: 4px;
  margin-top: 6px;
}

.register-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.register-desc {
  font-size: 13px;
  color: var(--fg-2);
  margin: 0;
}
</style>
