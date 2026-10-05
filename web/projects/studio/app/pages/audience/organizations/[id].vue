<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const activeTab = ref('Attributes')

const orgId = computed(() => route.params.id as string)

// ── Queries ──────────────────────────────────────────────────────────

const orgGql = gql`
  query GetOrganizationById($id: UUID!, $memberOffset: Long!) {
    security {
      principals {
        current {
          groups { name }
        }
      }
    }
    organizations {
      organization(id: $id) {
        id
        name
        visibility
        created
        modified
        attributes
        systemAttributes
        managers {
          id
          profiles { id name }
        }
        memberCount
        members(offset: $memberOffset, limit: 10) {
          profiles { id name }
        }
        permissions {
          groupType
          group { id name description }
          action
        }
        domains {
          domain
          autoJoin
          type
          group { id name }
        }
        signupEmails {
          email
          type
          group { id name }
        }
        signupTokens {
          type
          token
          group { id name description }
        }
        profile {
          id
          name
          slug
          type
          visibility
          attributes { typeId attributes }
          principal { verified }
        }
      }
    }
  }
`

// ── Mutations ────────────────────────────────────────────────────────

const editOrgGql = gql`
  mutation EditOrganization($organization: OrganizationInput!, $profile: ProfileInput!) {
    organizations {
      edit(organization: $organization, profile: $profile) { id name }
    }
  }
`

const addDomainGql = gql`
  mutation AddOrganizationDomain($id: UUID!, $domain: OrganizationDomainInput!) {
    organizations { addOrganizationDomain(id: $id, domain: $domain) { id } }
  }
`

const removeDomainGql = gql`
  mutation RemoveOrganizationDomain($id: UUID!, $domain: String!) {
    organizations { removeOrganizationDomain(id: $id, domain: $domain) { id } }
  }
`

const addEmailGql = gql`
  mutation AddSignupEmail($id: UUID!, $token: OrganizationSignupEmailInput!) {
    organizations { addSignupEmailToken(id: $id, token: $token) { id } }
  }
`

const removeEmailGql = gql`
  mutation RemoveSignupEmail($id: UUID!, $email: String!) {
    organizations { deleteSignupEmailToken(id: $id, token: $email) { id } }
  }
`

const addTokenGql = gql`
  mutation AddSignupToken($id: UUID!, $token: OrganizationSignupTokenInput!) {
    organizations { addSignupToken(id: $id, token: $token) { id } }
  }
`

const removeTokenGql = gql`
  mutation RemoveSignupToken($id: UUID!, $token: String!) {
    organizations { deleteSignupToken(id: $id, token: $token) { id } }
  }
`

// ── Types ───────────────────────────────────────────────────────────

interface OrgProfileAttribute {
  typeId: string
  attributes: Record<string, unknown>
}

interface OrgProfile {
  id: string
  name: string
  slug: string | null
  type: string
  visibility: string
  attributes: OrgProfileAttribute[]
  principal: { verified: boolean } | null
}

interface OrgPermission {
  groupType: string
  group: { id: string; name: string; description: string } | null
  action: string
}

interface OrgDomain {
  domain: string
  autoJoin: boolean
  type: string
  group: { id: string; name: string } | null
}

interface OrgSignupEmail {
  email: string
  type: string
  group: { id: string; name: string } | null
}

interface OrgSignupToken {
  type: string
  token: string
  group: { id: string; name: string; description: string } | null
}

interface OrgMember {
  id: string
  profiles: Array<{ id: string; name: string }>
}

interface Organization {
  id: string
  name: string
  visibility: string
  created: string
  modified: string
  attributes: Record<string, unknown>
  systemAttributes: Record<string, unknown>
  managers: OrgMember[]
  memberCount: number
  members: OrgMember[]
  permissions: OrgPermission[]
  domains: OrgDomain[]
  signupEmails: OrgSignupEmail[]
  signupTokens: OrgSignupToken[]
  profile: OrgProfile | null
}

// ── Data fetching ────────────────────────────────────────────────────

const memberPage = ref(1)
const memberOffset = computed(() => (memberPage.value - 1) * 10)

const { data, status, refresh } = useAsyncQuery<{
  security: { principals: { current: { groups: { name: string }[] } } }
  organizations: { organization: Organization | null }
}>('org-detail', orgGql, { id: orgId, memberOffset })

const org = computed(() => data.value?.organizations?.organization ?? null)
const profile = computed(() => org.value?.profile ?? null)
const isLoading = computed(() => status.value === 'pending')

const isSystemAdmin = computed(() => {
  const groups = data.value?.security?.principals?.current?.groups ?? []
  return groups.some((g) => g.name === 'administrators')
})

const avatarUrl = computed(() => {
  const attrs = profile.value?.attributes ?? []
  return attrs.find((a) => a.typeId === 'bosca.profiles.avatar')?.attributes?.picture ?? ''
})

const managers = computed(() => org.value?.managers ?? [])
const members = computed(() => org.value?.members ?? [])
const memberCount = computed(() => org.value?.memberCount ?? 0)
const totalMemberPages = computed(() => Math.ceil(memberCount.value / 10))

const domains = computed(() => org.value?.domains ?? [])
const signupEmails = computed(() => org.value?.signupEmails ?? [])
const signupTokens = computed(() => org.value?.signupTokens ?? [])

// ── UI state ─────────────────────────────────────────────────────────

const editModalOpen = ref(false)
const saving = ref(false)
const editForm = reactive({ name: '', slug: '', visibility: 'PUBLIC' })

const confirmOpen = ref(false)
const confirmTitle = ref('')
const confirmLoading = ref(false)
let confirmAction: (() => Promise<void>) | null = null

const addDomainModalOpen = ref(false)
const newDomain = reactive({ domain: '', autoJoin: true, type: 'USERS' })

const addEmailModalOpen = ref(false)
const newEmail = reactive({ email: '', type: 'USERS' })

const addTokenModalOpen = ref(false)
const newTokenType = ref('USERS')

const editingOrgAttrs = ref(false)
const editOrgAttrsData = ref<Record<string, unknown>>({})
const savingOrgAttrs = ref(false)

// ── Helpers ──────────────────────────────────────────────────────────

function formatDate(d: string | null | undefined): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function openConfirm(title: string, action: () => Promise<void>) {
  confirmTitle.value = title
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

function copyToClipboard(text: string) {
  navigator.clipboard.writeText(text).then(
    () => toast.success('Copied to clipboard'),
    () => toast.error('Failed to copy'),
  )
}

// ── Actions ──────────────────────────────────────────────────────────

function openEdit() {
  if (!org.value) return
  editForm.name = org.value.name
  editForm.slug = profile.value?.slug ?? ''
  editForm.visibility = org.value.visibility
  editModalOpen.value = true
}

async function onSaveOrg() {
  if (!org.value) return
  saving.value = true
  try {
    await gqlMutation(editOrgGql, {
      organization: {
        id: orgId.value,
        name: editForm.name.trim(),
        slug: editForm.slug.trim() || null,
        visibility: editForm.visibility.toUpperCase(),
        attributes: org.value.attributes ?? {},
        systemAttributes: org.value.systemAttributes ?? {},
        domains: [],
        signupEmails: [],
        signupTokens: [],
      },
      profile: {
        name: editForm.name.trim(),
        slug: editForm.slug.trim() || null,
        visibility: editForm.visibility.toUpperCase(),
        attributes: [],
      },
    })
    editModalOpen.value = false
    toast.success('Organization updated')
    await refresh()
  } catch (e: unknown) {
    const err = e as { errors?: Array<{ message: string }>; message?: string }
    toast.error(err?.errors?.[0]?.message ?? err?.message ?? 'Failed to update organization')
  } finally {
    saving.value = false
  }
}

async function onAddDomain() {
  if (!newDomain.domain.trim()) return
  try {
    await gqlMutation(addDomainGql, {
      id: orgId.value,
      domain: {
        domain: newDomain.domain.trim(),
        autoJoin: newDomain.autoJoin,
        type: newDomain.type,
      },
    })
    addDomainModalOpen.value = false
    newDomain.domain = ''
    newDomain.autoJoin = true
    newDomain.type = 'USERS'
    toast.success('Domain added')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add domain')
  }
}

function onRemoveDomain(domain: string) {
  openConfirm(`Remove domain "${domain}"?`, async () => {
    try {
      await gqlMutation(removeDomainGql, { id: orgId.value, domain })
      toast.success('Domain removed')
      refresh()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to remove domain')
    }
  })
}

async function onAddEmail() {
  if (!newEmail.email.trim()) return
  try {
    await gqlMutation(addEmailGql, {
      id: orgId.value,
      token: { email: newEmail.email.trim(), type: newEmail.type },
    })
    addEmailModalOpen.value = false
    newEmail.email = ''
    newEmail.type = 'USERS'
    toast.success('Email added')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add email')
  }
}

function onRemoveEmail(email: string) {
  openConfirm(`Remove signup email "${email}"?`, async () => {
    try {
      await gqlMutation(removeEmailGql, { id: orgId.value, email })
      toast.success('Email removed')
      refresh()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to remove email')
    }
  })
}

async function onAddToken() {
  try {
    await gqlMutation(addTokenGql, {
      id: orgId.value,
      token: { type: newTokenType.value },
    })
    addTokenModalOpen.value = false
    newTokenType.value = 'USERS'
    toast.success('Token created')
    refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to create token')
  }
}

function onRemoveToken(token: string) {
  openConfirm('Remove this signup token?', async () => {
    try {
      await gqlMutation(removeTokenGql, { id: orgId.value, token })
      toast.success('Token removed')
      refresh()
    } catch (e: unknown) {
      toast.error(e instanceof Error ? e.message : 'Failed to remove token')
    }
  })
}

function openOrgAttrEditor() {
  editOrgAttrsData.value = JSON.parse(JSON.stringify(org.value?.attributes ?? {}))
  editingOrgAttrs.value = true
}

function cancelOrgAttrEditor() {
  editingOrgAttrs.value = false
}

async function saveOrgAttrs() {
  if (!org.value) return
  savingOrgAttrs.value = true
  try {
    await gqlMutation(editOrgGql, {
      organization: {
        id: orgId.value,
        name: org.value.name,
        slug: profile.value?.slug ?? null,
        visibility: org.value.visibility.toUpperCase(),
        attributes: editOrgAttrsData.value,
        systemAttributes: org.value.systemAttributes ?? {},
        domains: [],
        signupEmails: [],
        signupTokens: [],
      },
      profile: {
        name: org.value.name,
        slug: profile.value?.slug ?? null,
        visibility: org.value.visibility.toUpperCase(),
        attributes: [],
      },
    })
    editingOrgAttrs.value = false
    toast.success('Attributes saved')
    await refresh()
  } catch (e: unknown) {
    const err = e as { errors?: Array<{ message: string }>; message?: string }
    toast.error(err?.errors?.[0]?.message ?? err?.message ?? 'Failed to save attributes')
  } finally {
    savingOrgAttrs.value = false
  }
}

const GROUP_TYPE_OPTIONS = [
  { value: 'USERS', label: 'Users' },
  { value: 'ADMINISTRATORS', label: 'Administrators' },
]


</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Audience', 'Organizations', org?.name ?? '…')"
        :title="org?.name ?? 'Loading…'"
        :subtitle="org ? `${memberCount} members · ${org.visibility}` : ''"
        :tabs="['Attributes', 'Members', 'Signup']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Button size="sm" icon="pencil" @click="openEdit">Edit</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !org" class="loading-state">Loading…</div>

    <template v-else-if="org">
      <!-- ── Attributes tab ───────────────────────────────── -->
      <div v-if="activeTab === 'Attributes'" class="detail-layout">
        <div class="main-content">
          <SectionCard title="Attributes">
            <template #right>
              <template v-if="editingOrgAttrs">
                <Button size="sm" @click="cancelOrgAttrEditor">Cancel</Button>
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  :disabled="savingOrgAttrs"
                  @click="saveOrgAttrs">
                  {{ savingOrgAttrs ? 'Saving…' : 'Save' }}
                </Button>
              </template>
              <Button
                v-else
                size="sm"
                icon="pencil"
                @click="openOrgAttrEditor">Edit</Button>
            </template>
            <div class="card-body">
              <div v-if="editingOrgAttrs">
                <JsonEditorVue
                  v-model="editOrgAttrsData"
                  :main-menu-bar="false"
                  :navigation-bar="false"
                  :status-bar="false"
                  class="json-editor"
                />
              </div>
              <div v-else-if="!org.attributes || !Object.keys(org.attributes).length" class="empty-state">
                No attributes
              </div>
              <JsonEditorVue
                v-else
                :model-value="org.attributes"
                :main-menu-bar="false"
                :navigation-bar="false"
                :status-bar="false"
                read-only
                class="json-editor"
              />
            </div>
          </SectionCard>
        </div>

        <div class="sidebar">
          <SectionCard>
            <div class="card-body">
              <div v-if="profile" class="identity-header" @click="router.push(`/audience/profiles/${profile.id}`)">
                <Avatar :name="profile.name" :src="avatarUrl || undefined" :size="48" />
                <div>
                  <div class="identity-name">{{ profile.name }}</div>
                  <div class="identity-sub">{{ profile.type }} · {{ profile.visibility }}</div>
                </div>
              </div>
              <div v-else class="empty-state">No profile attached</div>
            </div>
          </SectionCard>

          <SectionCard title="Details">
            <div class="card-body">
              <div class="meta-grid">
                <div class="meta-item">
                  <span class="meta-label">ID</span>
                  <button class="id-value-btn" :title="org.id" @click="copyToClipboard(org.id)">
                    <span class="meta-value mono id-value">{{ org.id }}</span>
                    <Icon name="copy" :size="11" color="var(--fg-3)" />
                  </button>
                </div>
                <div v-if="profile?.slug" class="meta-item">
                  <span class="meta-label">Slug</span>
                  <span class="meta-value mono">{{ profile.slug }}</span>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Visibility</span>
                  <Badge :color="org.visibility === 'PUBLIC' ? 'var(--ok)' : 'var(--fg-3)'">{{ org.visibility }}</Badge>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Members</span>
                  <span class="meta-value mono tabular">{{ memberCount.toLocaleString() }}</span>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Created</span>
                  <span class="meta-value">{{ formatDate(org.created) }}</span>
                </div>
                <div class="meta-item">
                  <span class="meta-label">Modified</span>
                  <span class="meta-value">{{ formatDate(org.modified) }}</span>
                </div>
              </div>
            </div>
          </SectionCard>

          <SectionCard v-if="org.permissions?.length" title="Permissions">
            <div class="permissions-table">
              <div v-for="(p, i) in org.permissions" :key="i" class="permission-row">
                <div class="permission-info">
                  <span class="permission-action">{{ p.action }}</span>
                  <button
                    v-if="p.group"
                    class="id-value-btn"
                    :title="p.group.id"
                    @click="copyToClipboard(p.group.id)">
                    <span class="permission-group">{{ p.group.name }}</span>
                    <Icon name="copy" :size="10" color="var(--fg-3)" />
                  </button>
                </div>
                <Badge :color="p.groupType === 'ADMINISTRATORS' ? 'var(--warn)' : accent">{{ p.groupType }}</Badge>
              </div>
            </div>
          </SectionCard>
        </div>
      </div>

      <!-- ── Members tab ──────────────────────────────────── -->
      <div v-if="activeTab === 'Members'">
        <div class="members-layout">
          <SectionCard title="Managers">
            <div class="card-body">
              <div v-if="!managers.length" class="empty-state">No managers</div>
              <div v-else class="member-list">
                <NuxtLink
                  v-for="m in managers"
                  :key="m.id"
                  :to="m.profiles?.[0]?.id ? `/audience/profiles/${m.profiles[0].id}` : '#'"
                  class="member-row"
                >
                  <Avatar :name="m.profiles?.[0]?.name ?? ''" :size="28" />
                  <span class="member-name">{{ m.profiles?.[0]?.name ?? '—' }}</span>
                  <Badge color="var(--warn)">Manager</Badge>
                </NuxtLink>
              </div>
            </div>
          </SectionCard>

          <SectionCard :title="`Members · ${memberCount}`">
            <div class="card-body">
              <div v-if="!members.length" class="empty-state">No members</div>
              <div v-else class="member-list">
                <NuxtLink
                  v-for="(m, i) in members"
                  :key="i"
                  :to="m.profiles?.[0]?.id ? `/audience/profiles/${m.profiles[0].id}` : '#'"
                  class="member-row"
                >
                  <Avatar :name="m.profiles?.[0]?.name ?? ''" :size="28" />
                  <span class="member-name">{{ m.profiles?.[0]?.name ?? '—' }}</span>
                </NuxtLink>
              </div>
              <Pagination
                v-if="totalMemberPages > 1"
                :page="memberPage"
                :total-pages="totalMemberPages"
                @prev="memberPage = Math.max(1, memberPage - 1)"
                @next="memberPage = Math.min(totalMemberPages, memberPage + 1)"
              />
            </div>
          </SectionCard>
        </div>
      </div>

      <!-- ── Signup tab ───────────────────────────────────── -->
      <div v-if="activeTab === 'Signup'" class="signup-tab">
        <SectionCard v-if="isSystemAdmin" title="Signup Domains">
          <template #right>
            <Button size="sm" icon="plus" @click="addDomainModalOpen = true">Add</Button>
          </template>
          <div class="card-body">
            <div v-if="!domains.length" class="empty-state">No domains configured</div>
            <div v-else class="item-list">
              <div v-for="(d, i) in domains" :key="i" class="item-row">
                <div class="item-info">
                  <span class="item-primary">{{ d.domain }}</span>
                  <span class="item-secondary">
                    Auto-join: {{ d.autoJoin ? 'Yes' : 'No' }} · Type: {{ d.type }}
                  </span>
                </div>
                <button class="remove-btn" @click="onRemoveDomain(d.domain)">
                  <Icon name="trash" :size="12" color="var(--fg-3)" />
                </button>
              </div>
            </div>
          </div>
        </SectionCard>

        <SectionCard v-if="isSystemAdmin" title="Signup Emails">
          <template #right>
            <Button size="sm" icon="plus" @click="addEmailModalOpen = true">Add</Button>
          </template>
          <div class="card-body">
            <div v-if="!signupEmails.length" class="empty-state">No signup emails</div>
            <div v-else class="item-list">
              <div v-for="(e, i) in signupEmails" :key="i" class="item-row">
                <div class="item-info">
                  <span class="item-primary">{{ e.email }}</span>
                  <span class="item-secondary">Type: {{ e.type }}</span>
                </div>
                <button class="remove-btn" @click="onRemoveEmail(e.email)">
                  <Icon name="trash" :size="12" color="var(--fg-3)" />
                </button>
              </div>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="Signup Tokens">
          <template #right>
            <Button size="sm" icon="plus" @click="addTokenModalOpen = true">Add</Button>
          </template>
          <div class="card-body">
            <div v-if="!signupTokens.length" class="empty-state">No signup tokens</div>
            <div v-else class="token-list">
              <div v-for="(t, i) in signupTokens" :key="i" class="token-row">
                <div class="token-header">
                  <Badge :color="accent">{{ t.type }}</Badge>
                  <span v-if="t.group" class="token-group">{{ t.group.name }}</span>
                  <span class="spacer" />
                  <button class="copy-btn" @click="copyToClipboard(t.token)">
                    <Icon name="copy" :size="12" color="var(--fg-3)" />
                  </button>
                  <button class="remove-btn" @click="onRemoveToken(t.token)">
                    <Icon name="trash" :size="12" color="var(--fg-3)" />
                  </button>
                </div>
                <div class="token-value mono">{{ t.token }}</div>
              </div>
            </div>
          </div>
        </SectionCard>
      </div>
    </template>

    <div v-else class="empty-centered">
      Organization not found.
    </div>

    <!-- Edit Modal -->
    <Modal
      v-if="editModalOpen"
      title="Edit Organization"
      icon="building"
      :accent="accent"
      @close="editModalOpen = false"
    >
      <TextInput v-model="editForm.name" label="Name" />
      <TextInput v-model="editForm.slug" label="Slug" placeholder="Optional" />
      <Select
        v-model="editForm.visibility"
        label="Visibility"
        :options="[
          { value: 'PUBLIC', label: 'Public' },
          { value: 'FRIENDS', label: 'Friends' },
          { value: 'FRIENDS_OF_FRIENDS', label: 'Friends of Friends' },
          { value: 'USER', label: 'User' },
          { value: 'SYSTEM', label: 'System' },
        ]"
      />
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="editModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="saving"
          @click="onSaveOrg">
          {{ saving ? 'Saving…' : 'Save' }}
        </Button>
      </template>
    </Modal>

    <!-- Add Domain Modal -->
    <Modal
      v-if="addDomainModalOpen"
      title="Add Domain"
      icon="globe"
      :accent="accent"
      @close="addDomainModalOpen = false"
    >
      <TextInput v-model="newDomain.domain" label="Domain" placeholder="example.com" />
      <Select v-model="newDomain.type" label="Group Type" :options="GROUP_TYPE_OPTIONS" />
      <div class="checkbox-row">
        <input :id="'auto-join'" v-model="newDomain.autoJoin" type="checkbox" >
        <label for="auto-join" class="checkbox-label">Auto-join new signups</label>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="addDomainModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newDomain.domain.trim()"
          @click="onAddDomain">Add Domain</Button>
      </template>
    </Modal>

    <!-- Add Email Modal -->
    <Modal
      v-if="addEmailModalOpen"
      title="Add Signup Email"
      icon="mail"
      :accent="accent"
      @close="addEmailModalOpen = false"
    >
      <TextInput v-model="newEmail.email" label="Email" placeholder="user@example.com" />
      <Select v-model="newEmail.type" label="Group Type" :options="GROUP_TYPE_OPTIONS" />
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="addEmailModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newEmail.email.trim()"
          @click="onAddEmail">Add Email</Button>
      </template>
    </Modal>

    <!-- Add Token Modal -->
    <Modal
      v-if="addTokenModalOpen"
      title="Create Signup Token"
      icon="key"
      :accent="accent"
      @close="addTokenModalOpen = false"
    >
      <Select v-model="newTokenType" label="Group Type" :options="GROUP_TYPE_OPTIONS" />
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="addTokenModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          @click="onAddToken">Create Token</Button>
      </template>
    </Modal>

    <!-- Confirm Modal -->
    <ConfirmModal
      v-if="confirmOpen"
      :title="confirmTitle"
      confirm-label="Confirm"
      :loading="confirmLoading"
      @close="confirmOpen = false"
      @confirm="doConfirm"
    />
  </PageShell>
</template>

<style scoped>
.spacer {
  flex: 1;
}

.loading-state {
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

.main-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.sidebar {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.card-body {
  padding: 10px 16px 14px;
}

.empty-state {
  font-size: 12.5px;
  color: var(--fg-3);
}

.empty-centered {
  padding: 40px;
  text-align: center;
  font-size: 12.5px;
  color: var(--fg-3);
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

/* ── Identity header ── */
.identity-header {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  border-radius: 8px;
  padding: 4px;
  margin: -4px;
  transition: background 0.1s;
}

.identity-header:hover {
  background: var(--bg-2);
}

.identity-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--fg-0);
}

.identity-sub {
  font-size: 11.5px;
  color: var(--fg-3);
}

/* ── Meta ── */
.meta-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.meta-item {
  display: flex;
  justify-content: space-between;
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

/* ── Members tab ── */
.members-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
  align-items: start;
}

.member-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.member-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 7px;
  text-decoration: none;
  transition: background 0.1s;
}

.member-row:hover {
  background: var(--bg-2);
}

.member-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
  flex: 1;
}

/* ── Item list (domains, emails) ── */
.item-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  transition: background 0.1s;
}

.item-row:hover {
  background: var(--bg-2);
}

.item-info {
  flex: 1;
  min-width: 0;
}

.item-primary {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
  display: block;
}

.item-secondary {
  font-size: 11px;
  color: var(--fg-3);
}

/* ── Token list ── */
.token-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.token-row {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--bg-2);
  border: 1px solid var(--line);
}

.token-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.token-group {
  font-size: 11.5px;
  color: var(--fg-3);
}

.token-value {
  font-size: 11px;
  color: var(--fg-2);
  word-break: break-all;
  line-height: 1.5;
}

/* ── Remove / copy button ── */
.remove-btn,
.copy-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: none;
  border: none;
  cursor: pointer;
  transition: background 0.1s;
}

.remove-btn:hover {
  background: color-mix(in oklch, var(--err) 14%, transparent);
}

.copy-btn:hover {
  background: var(--bg-3);
}

/* ── JSON editor ── */
.json-editor {
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}

/* ── Signup tab ── */
.signup-tab {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* ── Permissions ── */
.permissions-table {
  display: flex;
  flex-direction: column;
}

.permission-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 16px;
  border-top: 1px solid var(--line);
}

.permission-row:first-child {
  border-top: none;
}

.permission-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.permission-action {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--fg-0);
}

.permission-group {
  font-size: 11px;
  color: var(--fg-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── Checkbox ── */
.checkbox-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.checkbox-label {
  font-size: 13px;
  color: var(--fg-1);
}
</style>
