<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn, OverflowMenuItem, SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query: gqlQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const router = useRouter()

const offset = ref(0)
const limit = ref(25)

const myTokensGql = gql`
  query GetMyApiTokens($limit: Int!, $offset: Long!) {
    security {
      apiTokens {
        list: my(limit: $limit, offset: $offset) {
          id principalId name description tokenPrefix scopes expiresAt lastUsedAt revokedAt active
        }
      }
    }
  }
`

const forPrincipalTokensGql = gql`
  query GetApiTokensForPrincipal($principalId: UUID!, $limit: Int!, $offset: Long!) {
    security {
      apiTokens {
        list: forPrincipal(principalId: $principalId, limit: $limit, offset: $offset) {
          id principalId name description tokenPrefix scopes expiresAt lastUsedAt revokedAt active
        }
      }
    }
  }
`

const scopesGql = gql`
  query GetAvailableScopes {
    security { apiTokens { availableScopes { name description } } }
  }
`

const createGql = gql`
  mutation CreateApiToken($input: ApiTokenInput!) {
    security { apiTokens { create(input: $input) { apiToken { id name tokenPrefix active } rawToken } } }
  }
`

const createForPrincipalGql = gql`
  mutation CreateApiTokenForPrincipal($principalId: UUID!, $input: ApiTokenInput!) {
    security { apiTokens { createForPrincipal(principalId: $principalId, input: $input) { apiToken { id name tokenPrefix active } rawToken } } }
  }
`

const revokeGql = gql`
  mutation RevokeApiToken($id: Long!) { security { apiTokens { revoke(id: $id) } } }
`

const deleteGql = gql`
  mutation DeleteApiToken($id: Long!) { security { apiTokens { delete(id: $id) } } }
`

const searchProfilesGql = gql`
  query SearchProfilesForTokens($query: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: ["_type = \\"profile\\""]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents {
          profile { id name principal { id } }
        }
      }
    }
  }
`

interface ApiToken {
  id: string
  principalId: string
  name: string
  description: string | null
  tokenPrefix: string
  scopes: string[]
  expiresAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  active: boolean
}

// --- Current user ---
const { $auth } = useNuxtApp()
const currentPrincipalId = computed(() => $auth?.currentUser?.id ?? null)
const currentProfileId = computed(() => $auth?.currentProfile?.id ?? null)
const currentProfileName = computed(() => $auth?.currentProfile?.name ?? 'Me')

// Map profile ID → principalId from search results (and seeded with current user)
const profileToPrincipal = reactive(new Map<string, string>())

// --- Owner filter ---
const SELF_OWNER = '__self__'
const selectedOwnerProfileId = ref<string>(SELF_OWNER)

// Profile label cache so the Select keeps showing the chosen name after picking.
const profileLabels = reactive(new Map<string, string>())

const selectedOwnerPrincipalId = computed<string | null>(() => {
  if (selectedOwnerProfileId.value === SELF_OWNER) return null
  return profileToPrincipal.get(selectedOwnerProfileId.value) ?? null
})

const ownerFilterOptions = computed<SelectOption[]>(() => {
  const opts: SelectOption[] = [{ value: SELF_OWNER, label: 'Me' }]
  const sel = selectedOwnerProfileId.value
  if (sel !== SELF_OWNER) {
    opts.push({ value: sel, label: profileLabels.get(sel) ?? sel })
  }
  return opts
})

async function searchProfilesRaw(q: string): Promise<SelectOption[]> {
  try {
    const result = await gqlQuery<{ search: { search: { documents: Array<{ profile: { id: string; name: string; principal: { id: string } | null } | null }> } } }>(searchProfilesGql, { query: q, limit: 20, offset: 0 })
    const out: SelectOption[] = []
    for (const d of (result?.search?.search?.documents ?? [])) {
      if (d.profile?.principal == null) continue
      const label = d.profile.name || d.profile.id
      profileToPrincipal.set(d.profile.id, d.profile.principal.id)
      profileLabels.set(d.profile.id, label)
      out.push({ value: d.profile.id, label })
    }
    return out
  } catch {
    return []
  }
}

// Owner filter search: prepend "Me" so the user can quickly return to their own tokens.
async function searchProfilesForFilter(q: string): Promise<SelectOption[]> {
  const results = await searchProfilesRaw(q)
  if (!q || 'me'.includes(q.toLowerCase())) {
    return [{ value: SELF_OWNER, label: 'Me' }, ...results]
  }
  return results
}

// Create modal search: no SELF_OWNER (current profile is already in static options).
async function searchProfiles(q: string): Promise<SelectOption[]> {
  return searchProfilesRaw(q)
}

const { data: scopesData } = useAsyncQuery<{
  security: { apiTokens: { availableScopes: { name: string; description: string }[] } }
}>('available-scopes', scopesGql, undefined, { server: false })

const availableScopes = computed(() => scopesData.value?.security?.apiTokens?.availableScopes ?? [])

const { data: tokensData, status, refresh } = useAsyncData<ApiToken[]>(
  'api-tokens-list',
  async () => {
    const principalId = selectedOwnerPrincipalId.value
    if (principalId) {
      const r = await gqlQuery<{ security: { apiTokens: { list: ApiToken[] } } }>(
        forPrincipalTokensGql,
        { principalId, limit: limit.value, offset: offset.value },
      )
      return r?.security?.apiTokens?.list ?? []
    }
    const r = await gqlQuery<{ security: { apiTokens: { list: ApiToken[] } } }>(
      myTokensGql,
      { limit: limit.value, offset: offset.value },
    )
    return r?.security?.apiTokens?.list ?? []
  },
  { watch: [selectedOwnerPrincipalId, limit, offset], default: () => [] },
)

const tokens = computed(() => tokensData.value ?? [])
const tokensLoading = computed(() => status.value === 'pending')

const viewingOther = computed(() => selectedOwnerProfileId.value !== SELF_OWNER)
const ownerDisplayName = computed(() => {
  if (!viewingOther.value) return 'Me'
  return profileLabels.get(selectedOwnerProfileId.value) ?? 'Selected profile'
})

// --- Create flow ---
const showCreate = ref(false)
const newName = ref('')
const newDesc = ref('')
const newScopes = ref<string[]>([])
const newOwnerProfileId = ref<string>('')

function defaultExpiration(): string {
  const d = new Date()
  d.setDate(d.getDate() + 90)
  return d.toISOString().slice(0, 16)
}

const newExpiresAt = ref(defaultExpiration())
const showScopeBuilder = ref(false)
const saving = ref(false)
const createdToken = ref<string | null>(null)
const tokenCopied = ref(false)

function openCreate() {
  newName.value = ''
  newDesc.value = ''
  newScopes.value = []
  newExpiresAt.value = defaultExpiration()
  if (currentProfileId.value && currentPrincipalId.value) {
    profileToPrincipal.set(currentProfileId.value, currentPrincipalId.value)
    profileLabels.set(currentProfileId.value, currentProfileName.value)
  }
  // Default the create modal's owner to whichever owner the user is currently filtering by.
  if (viewingOther.value) {
    newOwnerProfileId.value = selectedOwnerProfileId.value
  } else {
    newOwnerProfileId.value = currentProfileId.value ?? ''
  }
  showCreate.value = true
}

const createOwnerOptions = computed<SelectOption[]>(() => {
  const opts: SelectOption[] = []
  if (currentProfileId.value) {
    opts.push({ value: currentProfileId.value, label: currentProfileName.value })
  }
  const picked = newOwnerProfileId.value
  if (picked && picked !== currentProfileId.value) {
    opts.push({ value: picked, label: profileLabels.get(picked) ?? picked })
  }
  return opts
})

async function handleCreate() {
  if (!newName.value.trim()) { toast.error('Name is required'); return }
  saving.value = true
  try {
    const resolvedPrincipalId = profileToPrincipal.get(newOwnerProfileId.value)
    const isCurrentUser = resolvedPrincipalId === currentPrincipalId.value || !newOwnerProfileId.value

    const input = {
      name: newName.value,
      description: newDesc.value || null,
      scopes: newScopes.value.length > 0 ? newScopes.value : null,
      expiresAt: newExpiresAt.value ? new Date(newExpiresAt.value).toISOString() : null,
    }

    let rawToken: string
    if (isCurrentUser) {
      const result = await gqlMutation<{ security: { apiTokens: { create: { rawToken: string } } } }>(
        createGql, { input },
      )
      rawToken = result.security.apiTokens.create.rawToken
    } else {
      const result = await gqlMutation<{ security: { apiTokens: { createForPrincipal: { rawToken: string } } } }>(
        createForPrincipalGql, { principalId: resolvedPrincipalId, input },
      )
      rawToken = result.security.apiTokens.createForPrincipal.rawToken
    }

    createdToken.value = rawToken
    tokenCopied.value = false
    showCreate.value = false
    refresh()
  } catch { toast.error('Failed to create token') }
  finally { saving.value = false }
}

async function copyToken() {
  if (!createdToken.value) return
  try {
    await navigator.clipboard.writeText(createdToken.value)
    tokenCopied.value = true
    toast.success('Token copied')
  } catch { toast.error('Failed to copy') }
}

// --- Revoke ---
const revokeTarget = ref<ApiToken | null>(null)
const revokeLoading = ref(false)

async function confirmRevoke() {
  if (!revokeTarget.value) return
  revokeLoading.value = true
  try {
    await gqlMutation(revokeGql, { id: parseInt(revokeTarget.value.id) })
    revokeTarget.value = null
    toast.success('Token revoked')
    refresh()
  } catch { toast.error('Failed to revoke') }
  finally { revokeLoading.value = false }
}

// --- Delete ---
const deleteTarget = ref<ApiToken | null>(null)
const deleteLoading = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await gqlMutation(deleteGql, { id: parseInt(deleteTarget.value.id) })
    deleteTarget.value = null
    toast.success('Token deleted')
    refresh()
  } catch { toast.error('Failed to delete') }
  finally { deleteLoading.value = false }
}

// --- Table ---
const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(180px, 2fr)' },
  { key: 'status', label: 'Status', width: '100px' },
  { key: 'scopes', label: 'Scopes', width: '1fr', muted: true },
  { key: 'expires', label: 'Expires', width: '120px', muted: true },
  { key: 'lastUsed', label: 'Last Used', width: '120px', muted: true },
]

function tokenStatus(t: ApiToken): [string, string] {
  if (t.revokedAt) return ['Revoked', '#f87171']
  if (t.expiresAt && new Date(t.expiresAt) < new Date()) return ['Expired', '#ffb547']
  return t.active ? ['Active', '#34d99a'] : ['Inactive', '#6c7388']
}

function getRowActions(row: ApiToken): OverflowMenuItem[] {
  const items: OverflowMenuItem[] = [
    { id: 'view', label: 'View Details', icon: 'list' },
    { id: 'copy', label: 'Copy ID', icon: 'copy' },
  ]
  if (row.active && !row.revokedAt) {
    items.push({ id: 'sep', label: '', separator: true })
    items.push({ id: 'revoke', label: 'Revoke', icon: 'x', danger: true })
  }
  if (row.revokedAt) {
    items.push({ id: 'sep', label: '', separator: true })
    items.push({ id: 'delete', label: 'Delete', icon: 'trash', danger: true })
  }
  return items
}

function onRowAction(action: string, row: ApiToken) {
  if (action === 'view') router.push(`/system/security/tokens/${row.id}`)
  else if (action === 'copy') { navigator.clipboard.writeText(row.id); toast.success('ID copied') }
  else if (action === 'revoke') revokeTarget.value = row
  else if (action === 'delete') deleteTarget.value = row
}

function formatDate(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Security', 'API Tokens')"
        title="API Tokens"
        :subtitle="`${tokens.length} token${tokens.length === 1 ? '' : 's'} · ${ownerDisplayName}`"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="openCreate">Create Token</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Tokens">
      <template #right>
        <div class="owner-filter">
          <Select
            v-model="selectedOwnerProfileId"
            :options="ownerFilterOptions"
            :on-search="searchProfilesForFilter"
            searchable
            size="sm"
            icon="user"
            placeholder="Filter by owner…"
            :accent="accent"
          />
        </div>
      </template>
      <GlassTable
        :columns="columns"
        :rows="tokens"
        :loading="tokensLoading && tokens.length === 0"
        :empty-text="viewingOther ? 'No tokens for this owner.' : 'No API tokens yet. Create one for programmatic access to the API.'"
        :row-actions="getRowActions"
        @row-action="({ action, row }) => onRowAction(action, row as ApiToken)"
        @row-click="(row: any) => router.push(`/system/security/tokens/${row.id}`)"
      >
        <template #col-name="{ row }">
          <div class="token-name-cell">
            <div class="token-name">{{ row.name }}</div>
            <div class="token-prefix mono">{{ row.tokenPrefix }}…</div>
          </div>
        </template>
        <template #col-status="{ row }">
          <Badge :color="tokenStatus(row as ApiToken)[1]">{{ tokenStatus(row as ApiToken)[0] }}</Badge>
        </template>
        <template #col-scopes="{ row }">
          <template v-if="(row as ApiToken).scopes?.length">
            <span class="scope-list">
              {{ (row as ApiToken).scopes.slice(0, 3).join(', ') }}
              <span v-if="(row as ApiToken).scopes.length > 3" class="scope-more">+{{ (row as ApiToken).scopes.length - 3 }} more</span>
            </span>
          </template>
          <span v-else class="muted">Unrestricted</span>
        </template>
        <template #col-expires="{ row }">
          <span v-if="(row as ApiToken).expiresAt">{{ formatDate((row as ApiToken).expiresAt) }}</span>
          <span v-else class="muted">Never</span>
        </template>
        <template #col-lastUsed="{ row }">
          <span v-if="(row as ApiToken).lastUsedAt">{{ formatDate((row as ApiToken).lastUsedAt) }}</span>
          <span v-else class="muted">Never</span>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- Create Token Modal -->
    <Modal
      v-if="showCreate"
      title="Create API Token"
      icon="key"
      :accent="accent"
      @close="showCreate = false">
      <div class="create-form">
        <Select
          v-model="newOwnerProfileId"
          :options="createOwnerOptions"
          :on-search="searchProfiles"
          searchable
          label="Owner"
          placeholder="Search profiles…"
          :accent="accent"
        />
        <TextInput
          v-model="newName"
          label="Name"
          placeholder="e.g. CI Deploy Token"
          autofocus />
        <Textarea
          v-model="newDesc"
          label="Description"
          placeholder="What is this token for?"
          :rows="2" />
        <DateInput v-model="newExpiresAt" label="Expiration" type="datetime-local" />
        <div class="field-hint">Defaults to 90 days. Clear for no expiration.</div>
        <div class="scopes-field">
          <div class="scopes-field-label">Scopes</div>
          <div v-if="newScopes.length" class="scopes-field-tags">
            <Badge v-for="scope in newScopes" :key="scope" color="var(--fg-3)">{{ scope }}</Badge>
          </div>
          <div v-else class="scopes-field-empty">Unrestricted — all permissions</div>
          <Button
            size="sm"
            icon="key"
            :accent="accent"
            @click="showScopeBuilder = true">{{ newScopes.length ? 'Edit Scopes' : 'Configure Scopes' }}</Button>
        </div>
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button size="sm" @click="showCreate = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newName.trim() || !newOwnerProfileId || saving"
          @click="handleCreate">Create</Button>
      </template>
    </Modal>

    <!-- Scope Builder Modal -->
    <Modal
      v-if="showScopeBuilder"
      title="Configure Scopes"
      icon="key"
      :accent="accent"
      width="640px"
      @close="showScopeBuilder = false">
      <div class="scope-builder-modal-body">
        <ScopeBuilder v-model="newScopes" :available-scopes="availableScopes" :accent="accent" />
      </div>
      <template #footer>
        <span style="flex: 1" />
        <Button
          size="sm"
          primary
          :accent="accent"
          @click="showScopeBuilder = false">Done</Button>
      </template>
    </Modal>

    <!-- Token Created Success Modal -->
    <Modal
      v-if="createdToken"
      title="Token Created"
      icon="check"
      accent="#34d99a"
      @close="createdToken = null">
      <p class="created-warning">Copy this token now. You won't be able to see it again.</p>
      <div class="token-display">
        <code class="mono">{{ createdToken }}</code>
        <Button size="sm" :icon="tokenCopied ? 'check' : 'copy'" @click="copyToken">
          {{ tokenCopied ? 'Copied' : 'Copy' }}
        </Button>
      </div>
    </Modal>

    <!-- Revoke Confirmation -->
    <ConfirmModal
      v-if="revokeTarget"
      :title="`Revoke '${revokeTarget.name}'?`"
      subtitle="This token will immediately stop working and cannot be restored."
      confirm-label="Revoke"
      :loading="revokeLoading"
      @close="revokeTarget = null"
      @confirm="confirmRevoke"
    />

    <!-- Delete Confirmation -->
    <ConfirmModal
      v-if="deleteTarget"
      :title="`Delete '${deleteTarget.name}'?`"
      subtitle="This will permanently remove the token record. This cannot be undone."
      confirm-label="Delete"
      :loading="deleteLoading"
      @close="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </PageShell>
</template>

<style scoped>
.owner-filter {
  width: 240px;
}

.token-name-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.token-name {
  font-weight: 500;
  color: var(--fg-0);
}

.token-prefix {
  font-size: 11px;
  color: var(--fg-4);
}

.scope-list {
  font-size: 12px;
}

.scope-more {
  color: var(--fg-4);
  margin-left: 4px;
}

.muted {
  color: var(--fg-4);
}

.create-form {
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

.field-hint {
  font-size: 11px;
  color: var(--fg-4);
  margin-top: -8px;
}

.created-warning {
  font-size: 13px;
  color: var(--fg-1);
  margin: 0 0 10px;
}

.token-display {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow-x: auto;
}

.token-display code {
  font-size: 12px;
  color: var(--fg-0);
  word-break: break-all;
  flex: 1;
}
</style>
