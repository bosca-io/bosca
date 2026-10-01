<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const tokenGql = gql`
  query GetApiToken($id: Long!) {
    security {
      apiTokens {
        token(id: $id) {
          id principalId name description tokenPrefix scopes
          allowedGroups expiresAt lastUsedAt lastUsedIp revokedAt active
        }
      }
    }
  }
`

const scopesGql = gql`
  query GetAvailableScopesForEdit {
    security { apiTokens { availableScopes { name description } } }
  }
`

const editGql = gql`
  mutation EditApiToken($id: Long!, $name: String, $description: String, $scopes: [String!]) {
    security { apiTokens { edit(id: $id, name: $name, description: $description, scopes: $scopes) { id name description scopes } } }
  }
`

const revokeGql = gql`
  mutation RevokeApiToken($id: Long!) { security { apiTokens { revoke(id: $id) } } }
`

const deleteGql = gql`
  mutation DeleteApiToken($id: Long!) { security { apiTokens { delete(id: $id) } } }
`

interface TokenDetail {
  id: string
  principalId: string
  name: string
  description: string | null
  tokenPrefix: string
  scopes: string[] | null
  allowedGroups: string[] | null
  expiresAt: string | null
  lastUsedAt: string | null
  lastUsedIp: string | null
  revokedAt: string | null
  active: boolean
}

const tokenId = computed(() => {
  const n = Number(route.params.id)
  return Number.isNaN(n) ? -1 : n
})

const { data, status, refresh } = useAsyncQuery<{
  security: { apiTokens: { token: TokenDetail | null } }
}>('api-token-detail', tokenGql, { id: tokenId })

const { data: scopesData } = useAsyncQuery<{
  security: { apiTokens: { availableScopes: { name: string; description: string }[] } }
}>('available-scopes-edit', scopesGql, undefined, { server: false })

const token = computed(() => data.value?.security?.apiTokens?.token ?? null)
const availableScopes = computed(() => scopesData.value?.security?.apiTokens?.availableScopes ?? [])

const statusColor = computed(() => {
  if (!token.value) return '#6c7388'
  if (token.value.revokedAt) return '#f87171'
  if (token.value.expiresAt && new Date(token.value.expiresAt) < new Date()) return '#ffb547'
  return token.value.active ? '#34d99a' : '#6c7388'
})

const statusLabel = computed(() => {
  if (!token.value) return ''
  if (token.value.revokedAt) return 'Revoked'
  if (token.value.expiresAt && new Date(token.value.expiresAt) < new Date()) return 'Expired'
  return token.value.active ? 'Active' : 'Inactive'
})

// --- Edit ---
const showEdit = ref(false)
const editName = ref('')
const editDesc = ref('')
const editScopes = ref<string[]>([])
const showEditScopes = ref(false)
const editSaving = ref(false)

function openEdit() {
  if (!token.value) return
  editName.value = token.value.name
  editDesc.value = token.value.description ?? ''
  editScopes.value = token.value.scopes ? [...token.value.scopes] : []
  showEdit.value = true
}

async function handleEdit() {
  editSaving.value = true
  try {
    await gqlMutation(editGql, {
      id: tokenId.value,
      name: editName.value || null,
      description: editDesc.value || null,
      scopes: editScopes.value.length > 0 ? editScopes.value : null,
    })
    showEdit.value = false
    toast.success('Token updated')
    refresh()
  } catch { toast.error('Failed to update token') }
  finally { editSaving.value = false }
}

// --- Revoke ---
const showRevoke = ref(false)
const revokeLoading = ref(false)

async function confirmRevoke() {
  revokeLoading.value = true
  try {
    await gqlMutation(revokeGql, { id: tokenId.value })
    showRevoke.value = false
    toast.success('Token revoked')
    refresh()
  } catch { toast.error('Failed to revoke') }
  finally { revokeLoading.value = false }
}

// --- Delete ---
const showDelete = ref(false)
const deleteLoading = ref(false)

async function confirmDelete() {
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: tokenId.value })
    showDelete.value = false
    toast.success('Token deleted')
    router.push('/system/security/tokens')
  } catch { toast.error('Failed to delete') }
  finally { deleteLoading.value = false }
}

function formatDate(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleString()
}

function copyField(value: string) {
  navigator.clipboard.writeText(value)
  toast.success('Copied')
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Security', { label: 'API Tokens', to: '/system/security/tokens' }, token?.name ?? 'Token')"
        :title="token?.name ?? 'API Token'"
        :subtitle="token ? `${token.tokenPrefix}…` : ''"
      >
        <template v-if="token" #actions>
          <Button
            size="sm"
            icon="pencil"
            :accent="accent"
            @click="openEdit">Edit</Button>
          <Button
            v-if="token.active && !token.revokedAt"
            size="sm"
            icon="x"
            accent="#f87171"
            @click="showRevoke = true">Revoke</Button>
          <Button
            v-if="token.revokedAt"
            size="sm"
            icon="trash"
            accent="#f87171"
            @click="showDelete = true">Delete</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="status === 'pending' && !token" class="loading-state">Loading…</div>

    <div v-else-if="!token" class="empty-state">Token not found.</div>

    <div v-else class="detail-layout">
      <!-- Status & Details -->
      <SectionCard title="Details">
        <template #right>
          <Badge :color="statusColor">{{ statusLabel }}</Badge>
        </template>

        <div class="card-body detail-grid">
          <div v-if="token.description" class="detail-item span-2">
            <div class="detail-label">Description</div>
            <div class="detail-value">{{ token.description }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">Token ID</div>
            <div class="detail-value mono clickable" @click="copyField(token.id)">{{ token.id }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">Principal ID</div>
            <div class="detail-value mono clickable" @click="copyField(token.principalId)">{{ token.principalId }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">Expires</div>
            <div class="detail-value">{{ token.expiresAt ? formatDate(token.expiresAt) : 'Never' }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">Last Used</div>
            <div class="detail-value">{{ token.lastUsedAt ? formatDate(token.lastUsedAt) : 'Never' }}</div>
          </div>
          <div v-if="token.lastUsedIp" class="detail-item">
            <div class="detail-label">Last Used IP</div>
            <div class="detail-value mono">{{ token.lastUsedIp }}</div>
          </div>
          <div v-if="token.revokedAt" class="detail-item">
            <div class="detail-label">Revoked At</div>
            <div class="detail-value">{{ formatDate(token.revokedAt) }}</div>
          </div>
        </div>
      </SectionCard>

      <!-- Scopes -->
      <SectionCard title="Scopes">
        <div class="card-body">
          <div v-if="!token.scopes || token.scopes.length === 0" class="muted">
            Unrestricted — this token has all permissions of the owning principal.
          </div>
          <div v-else class="scope-tags">
            <Badge v-for="scope in token.scopes" :key="scope" color="var(--fg-3)">{{ scope }}</Badge>
          </div>
        </div>
      </SectionCard>

      <!-- Allowed Groups -->
      <SectionCard v-if="token.allowedGroups && token.allowedGroups.length > 0" title="Allowed Groups">
        <div class="card-body">
          <div class="scope-tags">
            <Badge v-for="groupId in token.allowedGroups" :key="groupId" color="var(--fg-3)">
              <span class="mono">{{ groupId }}</span>
            </Badge>
          </div>
        </div>
      </SectionCard>
    </div>

    <!-- Edit Modal -->
    <Modal
      v-if="showEdit"
      title="Edit Token"
      icon="pencil"
      :accent="accent"
      @close="showEdit = false">
      <div class="edit-form">
        <TextInput
          v-model="editName"
          label="Name"
          placeholder="Token name"
          autofocus />
        <Textarea
          v-model="editDesc"
          label="Description"
          placeholder="Description"
          :rows="2" />
        <div class="scopes-field">
          <div class="scopes-field-label">Scopes</div>
          <div v-if="editScopes.length" class="scopes-field-tags">
            <Badge v-for="scope in editScopes" :key="scope" color="var(--fg-3)">{{ scope }}</Badge>
          </div>
          <div v-else class="scopes-field-empty">Unrestricted — all permissions</div>
          <Button
            size="sm"
            icon="key"
            :accent="accent"
            @click="showEditScopes = true">{{ editScopes.length ? 'Edit Scopes' : 'Configure Scopes' }}</Button>
        </div>
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showEdit = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="editSaving"
          @click="handleEdit">Save</Button>
      </template>
    </Modal>

    <!-- Scope Builder Modal (Edit) -->
    <Modal
      v-if="showEditScopes"
      title="Configure Scopes"
      icon="key"
      :accent="accent"
      width="640px"
      @close="showEditScopes = false">
      <div class="scope-builder-modal-body">
        <ScopeBuilder v-model="editScopes" :available-scopes="availableScopes" :accent="accent" />
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button
          size="sm"
          primary
          :accent="accent"
          @click="showEditScopes = false">Done</Button>
      </template>
    </Modal>

    <!-- Revoke Confirmation -->
    <ConfirmModal
      v-if="showRevoke"
      :title="`Revoke '${token?.name}'?`"
      subtitle="This token will immediately stop working and cannot be restored."
      confirm-label="Revoke"
      :loading="revokeLoading"
      @close="showRevoke = false"
      @confirm="confirmRevoke"
    />

    <!-- Delete Confirmation -->
    <ConfirmModal
      v-if="showDelete"
      :title="`Delete '${token?.name}'?`"
      subtitle="This will permanently remove the token record. This cannot be undone."
      confirm-label="Delete"
      :loading="deleteLoading"
      @close="showDelete = false"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.detail-layout {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 1000px;
}

.card-body {
  padding: 14px 16px;
}

.loading-state,
.empty-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.detail-item.span-2 {
  grid-column: span 2;
}

.detail-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 3px;
}

.detail-value {
  font-size: 13px;
  color: var(--fg-0);
}

.detail-value.clickable {
  cursor: pointer;
}

.detail-value.clickable:hover {
  color: var(--brand-2);
}

.scope-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.muted {
  font-size: 13px;
  color: var(--fg-3);
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.scopes-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.scopes-field-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.scopes-field-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.scopes-field-empty {
  font-size: 12px;
  color: var(--fg-4);
}

.scope-builder-modal-body {
  overflow-y: auto;
  max-height: calc(90vh - 140px);
}
</style>
