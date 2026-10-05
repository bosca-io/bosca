<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const principalId = computed(() => route.params.id as string)

// ── Query ────────────────────────────────────────────────────────────

const principalGql = gql`
  query GetPrincipalById($id: UUID!) {
    security {
      principals {
        principal(id: $id) {
          id
          verified
          anonymous
          deletedAt
          primaryProfileId
          created
          modified
          lastLogin
          credentials { type identifier originator lastOriginator }
          profiles { id name deletedAt }
          groups { id name description type }
        }
      }
    }
  }
`

// ── Mutations ────────────────────────────────────────────────────────

const markDeletedGql = gql`
  mutation MarkPrincipalDeleted($id: UUID!) {
    security { admin { markPrincipalDeleted(id: $id) { id deletedAt } } }
  }
`

const restoreGql = gql`
  mutation RestorePrincipal($id: UUID!) {
    security { admin { restorePrincipal(id: $id) { id deletedAt } } }
  }
`

const deleteGql = gql`
  mutation DeletePrincipal($id: UUID!) {
    security { admin { deletePrincipal(id: $id) } }
  }
`

// ── Types ────────────────────────────────────────────────────────────

interface PrincipalCredential {
  type: string
  identifier: string
  originator: string | null
  lastOriginator: string | null
}

interface PrincipalProfile {
  id: string
  name: string
  deletedAt: string | null
}

interface PrincipalGroup {
  id: string
  name: string
  description: string
  type: string
}

interface Principal {
  id: string
  verified: boolean
  anonymous: boolean
  deletedAt: string | null
  primaryProfileId: string | null
  created: string
  modified: string
  lastLogin: string | null
  credentials: PrincipalCredential[]
  profiles: PrincipalProfile[]
  groups: PrincipalGroup[]
}

// ── Data ─────────────────────────────────────────────────────────────

const { data, status, refresh } = useAsyncQuery<{
  security: { principals: { principal: Principal | null } }
}>('principal-detail', principalGql, { id: principalId })

const principal = computed(() => data.value?.security?.principals?.principal ?? null)
const isLoading = computed(() => status.value === 'pending')
const principalDeleted = computed(() => !!principal.value?.deletedAt)

const identifier = computed(() => {
  const creds = principal.value?.credentials ?? []
  const primary = creds.find(c => c.type === 'PASSWORD' || c.type === 'EMAIL')
  return primary?.identifier ?? creds[0]?.identifier ?? principal.value?.id ?? ''
})

const systemGroups = computed(() =>
  (principal.value?.groups ?? []).filter(g => g.type === 'SYSTEM'),
)

// ── Confirm modal ────────────────────────────────────────────────────

const confirmOpen = ref(false)
const confirmTitle = ref('')
const confirmLabel = ref('Confirm')
const confirmLoading = ref(false)
let confirmAction: (() => Promise<void>) | null = null

function openConfirm(title: string, action: () => Promise<void>, label = 'Confirm') {
  confirmTitle.value = title
  confirmLabel.value = label
  confirmAction = action
  confirmOpen.value = true
}

async function doConfirm() {
  if (!confirmAction) return
  confirmLoading.value = true
  try {
    await confirmAction()
  } finally {
    confirmLoading.value = false
    confirmOpen.value = false
    confirmAction = null
  }
}

// ── Actions ──────────────────────────────────────────────────────────

const restoring = ref(false)

function copyToClipboard(text: string) {
  navigator.clipboard.writeText(text).then(
    () => toast.success('Copied to clipboard'),
    () => toast.error('Failed to copy'),
  )
}

function formatDate(d: string | null | undefined): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function formatDateTime(d: string | null | undefined): string {
  if (!d) return '—'
  return new Date(d).toLocaleString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

function onMarkDeleted() {
  openConfirm(
    'Mark this principal deleted? Their active sessions are revoked immediately and they cannot sign in until restored. This is reversible.',
    async () => {
      try {
        await gqlMutation(markDeletedGql, { id: principalId.value })
        toast.success('Principal marked deleted')
        await refresh()
      } catch (e: unknown) {
        toast.error(e instanceof Error ? e.message : 'Failed to mark principal deleted')
      }
    },
  )
}

async function onRestore() {
  restoring.value = true
  try {
    await gqlMutation(restoreGql, { id: principalId.value })
    toast.success('Principal restored')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to restore principal')
  } finally {
    restoring.value = false
  }
}

function onFullyDelete() {
  const p = principal.value
  if (!p) return
  const credCount = p.credentials?.length ?? 0
  const profCount = p.profiles?.length ?? 0
  const parts = [
    `${credCount} credential${credCount === 1 ? '' : 's'}`,
    'group memberships',
    `${profCount} linked profile${profCount === 1 ? '' : 's'}`,
  ]
  openConfirm(
    `Permanently delete this principal? This also destroys ${parts.join(', ')}. This cannot be undone.`,
    async () => {
      try {
        await gqlMutation(deleteGql, { id: principalId.value })
        toast.success('Principal deleted')
        router.push('/system/security/principals')
      } catch (e: unknown) {
        toast.error(e instanceof Error ? e.message : 'Failed to delete principal')
      }
    },
    'Delete',
  )
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Security', 'Principals', identifier || '…')"
        :title="identifier || 'Loading…'"
        :subtitle="principal ? (principal.verified ? 'Verified principal' : 'Unverified principal') : ''" />
    </template>

    <div v-if="isLoading && !principal" class="loading-state">Loading…</div>

    <template v-else-if="principal">
      <div class="detail-layout">
        <div class="main-content">
          <!-- Credentials -->
          <SectionCard :title="`Credentials · ${principal.credentials.length}`">
            <div class="card-body">
              <div v-if="!principal.credentials.length" class="empty-state">No credentials</div>
              <div v-else class="row-list">
                <div v-for="(c, i) in principal.credentials" :key="i" class="list-row">
                  <Badge color="var(--fg-3)">{{ c.type }}</Badge>
                  <span class="mono cred-id">{{ c.identifier }}</span>
                  <span v-if="c.originator || c.lastOriginator" class="cred-origin">
                    <span v-if="c.originator">from {{ c.originator }}</span>
                    <span v-if="c.lastOriginator && c.lastOriginator !== c.originator"> · last {{ c.lastOriginator }}</span>
                  </span>
                </div>
              </div>
            </div>
          </SectionCard>

          <!-- Linked profiles -->
          <SectionCard :title="`Linked Profiles · ${principal.profiles.length}`">
            <div class="card-body">
              <div v-if="!principal.profiles.length" class="empty-state">No linked profiles</div>
              <div v-else class="row-list">
                <NuxtLink
                  v-for="p in principal.profiles"
                  :key="p.id"
                  :to="`/audience/profiles/${p.id}`"
                  class="list-row profile-link"
                >
                  <Avatar :name="p.name" :size="24" />
                  <span class="profile-name">{{ p.name }}</span>
                  <Badge v-if="p.id === principal.primaryProfileId" :color="accent">Primary</Badge>
                  <Badge v-if="p.deletedAt" color="var(--err)">Deleted</Badge>
                </NuxtLink>
              </div>
            </div>
          </SectionCard>

          <!-- Security groups -->
          <SectionCard :title="`Security Groups · ${systemGroups.length}`">
            <div class="card-body">
              <div v-if="!systemGroups.length" class="empty-state">No groups</div>
              <div v-else class="row-list">
                <div v-for="g in systemGroups" :key="g.id" class="list-row">
                  <span class="group-name">{{ g.description || g.name }}</span>
                  <Badge color="var(--fg-3)">{{ g.type }}</Badge>
                </div>
              </div>
            </div>
          </SectionCard>
        </div>

        <div class="sidebar">
          <!-- Details -->
          <SectionCard title="Details">
            <div class="card-body">
              <div class="meta-grid">
                <div class="meta-item">
                  <span class="meta-label">ID</span>
                  <button class="id-value-btn" :title="principal.id" @click="copyToClipboard(principal.id)">
                    <span class="meta-value mono id-value">{{ principal.id }}</span>
                    <Icon name="copy" :size="11" color="var(--fg-3)" />
                  </button>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Verified</span>
                  <Badge :color="principal.verified ? 'var(--ok)' : 'var(--warn)'">
                    {{ principal.verified ? 'Yes' : 'No' }}
                  </Badge>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Anonymous</span>
                  <Badge :color="principal.anonymous ? 'var(--warn)' : 'var(--fg-3)'">
                    {{ principal.anonymous ? 'Yes' : 'No' }}
                  </Badge>
                </div>
                <div v-if="principalDeleted" class="meta-item">
                  <span class="meta-label">Status</span>
                  <Badge color="var(--err)">Marked deleted</Badge>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Created</span>
                  <span class="meta-value">{{ formatDate(principal.created) }}</span>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Last Login</span>
                  <span class="meta-value">{{ formatDateTime(principal.lastLogin) }}</span>
                </div>
              </div>
            </div>
          </SectionCard>

          <!-- Danger Zone -->
          <SectionCard title="Danger Zone">
            <div class="card-body danger-zone">
              <p class="danger-hint">
                <template v-if="principalDeleted">
                  This principal is staged for deletion — sessions are revoked and sign-in is blocked.
                  Restore to re-enable, or fully delete to remove it and its linked profiles permanently.
                </template>
                <template v-else>
                  Mark the principal deleted first (reversible — revokes sessions and blocks sign-in);
                  it can then be permanently deleted.
                </template>
              </p>
              <div class="danger-actions">
                <Button
                  v-if="!principalDeleted"
                  size="sm"
                  icon="trash"
                  class="danger-btn"
                  @click="onMarkDeleted">Mark Deleted</Button>
                <Button
                  v-if="principalDeleted"
                  size="sm"
                  icon="refresh"
                  :disabled="restoring"
                  @click="onRestore">Restore</Button>
                <Button
                  size="sm"
                  icon="trash"
                  class="danger-btn"
                  :disabled="!principalDeleted"
                  @click="onFullyDelete">Fully Delete</Button>
              </div>
            </div>
          </SectionCard>
        </div>
      </div>
    </template>

    <div v-else class="empty-centered">
      Principal not found.
    </div>

    <ConfirmModal
      v-if="confirmOpen"
      :title="confirmTitle"
      :confirm-label="confirmLabel"
      :loading="confirmLoading"
      @close="confirmOpen = false"
      @confirm="doConfirm"
    />
  </PageShell>
</template>

<style scoped>
.loading-state,
.empty-centered {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.detail-layout {
  display: grid;
  grid-template-columns: 1fr 280px;
  gap: 18px;
  align-items: start;
}

.main-content,
.sidebar {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.card-body {
  padding: 10px 16px 14px;
}

.empty-state {
  font-size: 12.5px;
  color: var(--fg-3);
}

.row-list {
  display: flex;
  flex-direction: column;
}

.list-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-top: 1px solid var(--line);
}

.list-row:first-child {
  border-top: none;
}

.profile-link {
  text-decoration: none;
  color: var(--fg-0);
}

.profile-link:hover .profile-name {
  text-decoration: underline;
}

.profile-name {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.group-name {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.cred-id {
  font-size: 12px;
  color: var(--fg-1);
  word-break: break-all;
}

.cred-origin {
  margin-left: auto;
  font-size: 11px;
  color: var(--fg-3);
  white-space: nowrap;
}

.meta-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.meta-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.meta-label {
  font-size: 12px;
  color: var(--fg-3);
  font-weight: 500;
}

.meta-value {
  font-size: 12px;
  color: var(--fg-1);
}

.id-value-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px 4px;
  margin: -2px -4px;
  border-radius: 4px;
  transition: background 0.1s;
  max-width: 180px;
}

.id-value-btn:hover {
  background: var(--bg-2);
}

.id-value {
  font-size: 10.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── Danger zone ── */
.danger-zone {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.danger-hint {
  margin: 0;
  font-size: 12px;
  color: var(--fg-3);
  line-height: 1.5;
}

.danger-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.danger-btn {
  color: var(--err);
}
</style>
