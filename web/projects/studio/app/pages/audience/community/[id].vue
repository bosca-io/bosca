<script setup lang="ts">
import gql from 'graphql-tag'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()
const activeTab = ref('Members')

const groupId = computed(() => route.params.id as string)

const groupGql = gql`
  query GetCommunityGroup($id: UUID!) {
    community {
      group(id: $id) {
        id
        name
        description
        type
        visibility
        attributes
        members {
          profile { id name }
        }
        activities {
          id
          name
          description
          type
          schedule
        }
        channels {
          id
          name
        }
        prayers(limit: 50, offset: 0) {
          prayers {
            id
            title
            content
            created
            modified
            status
            profile { id name }
          }
        }
        signupTokens {
          token
          created
          expires
        }
      }
    }
  }
`

const addMemberGql = gql`
  mutation AddCommunityMember($groupId: UUID!, $profileId: UUID!) {
    community { addMember(groupId: $groupId, profileId: $profileId) }
  }
`

const removeMemberGql = gql`
  mutation RemoveCommunityMember($groupId: UUID!, $profileId: UUID!) {
    community { removeMember(groupId: $groupId, profileId: $profileId) }
  }
`

const addTokenGql = gql`
  mutation AddCommunityGroupSignupToken($groupId: UUID!) {
    community { addCommunityGroupSignupToken(groupId: $groupId) { token created expires } }
  }
`

const updateGroupGql = gql`
  mutation UpdateCommunityGroup(
    $id: UUID!
    $name: String
    $description: String
    $type: CommunityGroupType
    $visibility: CommunityVisibility
  ) {
    community {
      updateGroup(id: $id, name: $name, description: $description, type: $type, visibility: $visibility) {
        id
        name
        description
        type
        visibility
      }
    }
  }
`

const deleteTokenGql = gql`
  mutation DeleteCommunityGroupSignupToken($groupId: UUID!, $token: String!) {
    community { deleteCommunityGroupSignupToken(groupId: $groupId, token: $token) }
  }
`

const addPrayerGql = gql`
  mutation AddPrayer($groupId: UUID!, $title: String!, $content: JSON!) {
    community { addPrayer(groupId: $groupId, title: $title, content: $content) { id } }
  }
`

const deletePrayerGql = gql`
  mutation DeletePrayer($id: UUID!) {
    community { deletePrayer(id: $id) }
  }
`

interface PrayerItem {
  id: string
  title: string
  content: unknown
  created: string
  modified: string
  status: string
  profile: { id: string; name: string }
}

interface GroupDetail {
  id: string
  name: string
  description: string
  type: string
  visibility: string
  attributes: Record<string, unknown> | null
  members: Array<{ profile: { id: string; name: string } }>
  activities: Array<{ id: string; name: string; description: string; type: string; schedule: unknown }>
  channels: Array<{ id: string; name: string }>
  prayers: { prayers: PrayerItem[] }
  signupTokens: Array<{ token: string; created: string; expires: string }>
}

const { data, status, error, refresh } = useAsyncQuery<{
  community: { group: GroupDetail | null }
}>(`community-group-${groupId.value}`, groupGql, { id: groupId })

const group = computed(() => data.value?.community?.group ?? null)
const isLoading = computed(() => status.value === 'pending')
const members = computed(() => group.value?.members ?? [])
const activities = computed(() => group.value?.activities ?? [])
const channels = computed(() => group.value?.channels ?? [])
const prayers = computed(() => group.value?.prayers?.prayers ?? [])
const tokens = computed(() => group.value?.signupTokens ?? [])

// ── Add member ──────────────────────────────────────────────────────
const addMemberModalOpen = ref(false)
const newProfileId = ref('')

async function onAddMember() {
  if (!newProfileId.value.trim()) return
  try {
    await gqlMutation(addMemberGql, {
      groupId: groupId.value,
      profileId: newProfileId.value.trim(),
    })
    toast.success('Member added')
    addMemberModalOpen.value = false
    newProfileId.value = ''
    await refresh()
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : 'Failed to add member'
    toast.error(message)
  }
}

function onRemoveMember(profileId: string) {
  confirmTitle.value = 'Remove this member?'
  confirmAction = async () => {
    try {
      await gqlMutation(removeMemberGql, { groupId: groupId.value, profileId })
      toast.success('Member removed')
      await refresh()
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to remove member'
      toast.error(message)
    }
  }
  confirmOpen.value = true
}

// ── Tokens ──────────────────────────────────────────────────────────
async function onAddToken() {
  try {
    await gqlMutation(addTokenGql, { groupId: groupId.value })
    toast.success('Signup token created')
    await refresh()
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : 'Failed to create token'
    toast.error(message)
  }
}

function onDeleteToken(token: string) {
  confirmTitle.value = 'Delete this signup token?'
  confirmAction = async () => {
    try {
      await gqlMutation(deleteTokenGql, { groupId: groupId.value, token })
      toast.success('Token deleted')
      await refresh()
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to delete token'
      toast.error(message)
    }
  }
  confirmOpen.value = true
}

// ── Prayers ─────────────────────────────────────────────────────────
const addPrayerModalOpen = ref(false)
const newPrayerTitle = ref('')
const newPrayerContent = ref('')

async function onAddPrayer() {
  if (!newPrayerTitle.value.trim()) return
  try {
    await gqlMutation(addPrayerGql, {
      groupId: groupId.value,
      title: newPrayerTitle.value.trim(),
      content: { text: newPrayerContent.value.trim() },
    })
    toast.success('Prayer added')
    addPrayerModalOpen.value = false
    newPrayerTitle.value = ''
    newPrayerContent.value = ''
    await refresh()
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : 'Failed to add prayer'
    toast.error(message)
  }
}

function onDeletePrayer(prayerId: string) {
  confirmTitle.value = 'Delete this prayer?'
  confirmAction = async () => {
    try {
      await gqlMutation(deletePrayerGql, { id: prayerId })
      toast.success('Prayer deleted')
      await refresh()
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to delete prayer'
      toast.error(message)
    }
  }
  confirmOpen.value = true
}

function prayerText(content: unknown): string {
  if (!content) return ''
  if (typeof content === 'string') return content
  if (typeof content === 'object' && content !== null && 'text' in content) {
    return (content as { text: string }).text
  }
  return JSON.stringify(content)
}

// ── Edit group ─────────────────────────────────────────────────────
const editGroup = reactive({
  name: '',
  description: '',
  type: '',
  visibility: '',
})
const saving = ref(false)

watch(group, (g) => {
  if (g) {
    editGroup.name = g.name
    editGroup.description = g.description
    editGroup.type = g.type
    editGroup.visibility = g.visibility
  }
}, { immediate: true })

const editDirty = computed(() => {
  if (!group.value) return false
  return (
    editGroup.name !== group.value.name ||
    editGroup.description !== group.value.description ||
    editGroup.type !== group.value.type ||
    editGroup.visibility !== group.value.visibility
  )
})

async function onSaveGroup() {
  if (!editDirty.value || !group.value) return
  saving.value = true
  try {
    const vars: Record<string, string> = { id: groupId.value }
    if (editGroup.name !== group.value.name) vars.name = editGroup.name
    if (editGroup.description !== group.value.description) vars.description = editGroup.description
    if (editGroup.type !== group.value.type) vars.type = editGroup.type
    if (editGroup.visibility !== group.value.visibility) vars.visibility = editGroup.visibility
    await gqlMutation(updateGroupGql, vars)
    toast.success('Group updated')
    await refresh()
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : 'Failed to update group'
    toast.error(message)
  } finally {
    saving.value = false
  }
}

// ── Confirm dialog ──────────────────────────────────────────────────
const confirmOpen = ref(false)
const confirmTitle = ref('')
const confirmLoading = ref(false)
let confirmAction: (() => Promise<void>) | null = null

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

// ── Helpers ─────────────────────────────────────────────────────────
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
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Audience', 'Community', group?.name ?? '…')"
        :title="group?.name ?? 'Loading…'"
        :subtitle="group ? `${members.length} members · ${group.type} · ${group.visibility}` : ''"
        :tabs="['Members', 'Prayers', 'Activities', 'Signup', 'Settings']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      />
    </template>

    <div v-if="isLoading && !group" class="loading-state">Loading…</div>

    <template v-else-if="group">
      <!-- ── Members tab ──────────────────────────────────── -->
      <div v-if="activeTab === 'Members'" class="tab-content">
        <div class="detail-layout">
          <div class="main-content">
            <SectionCard :title="`Members · ${members.length}`">
              <template #right>
                <Button size="sm" icon="plus" @click="addMemberModalOpen = true">Add</Button>
              </template>
              <div class="card-body">
                <div v-if="!members.length" class="empty-state">No members</div>
                <div v-else class="member-list">
                  <div
                    v-for="m in members"
                    :key="m.profile.id"
                    class="member-row"
                  >
                    <Avatar :name="m.profile.name" :size="28" />
                    <NuxtLink :to="`/audience/profiles/${m.profile.id}`" class="member-name">
                      {{ m.profile.name }}
                    </NuxtLink>
                    <span class="spacer" />
                    <button class="remove-btn" @click="onRemoveMember(m.profile.id)">
                      <Icon name="trash" :size="12" color="var(--fg-3)" />
                    </button>
                  </div>
                </div>
              </div>
            </SectionCard>

            <SectionCard v-if="channels.length" :title="`Channels · ${channels.length}`">
              <div class="card-body">
                <div class="item-list">
                  <div v-for="ch in channels" :key="ch.id" class="item-row">
                    <Icon name="message-circle" :size="14" color="var(--fg-3)" />
                    <span class="item-primary">{{ ch.name }}</span>
                  </div>
                </div>
              </div>
            </SectionCard>
          </div>

          <div class="sidebar">
            <SectionCard title="Details">
              <div class="card-body">
                <div class="meta-grid">
                  <div class="meta-item">
                    <span class="meta-label">ID</span>
                    <button class="id-value-btn" :title="group.id" @click="copyToClipboard(group.id)">
                      <span class="meta-value mono id-value">{{ group.id }}</span>
                      <Icon name="copy" :size="11" color="var(--fg-3)" />
                    </button>
                  </div>
                  <div class="meta-item">
                    <span class="meta-label">Type</span>
                    <Badge :color="accent">{{ group.type }}</Badge>
                  </div>
                  <div class="meta-item">
                    <span class="meta-label">Visibility</span>
                    <Badge :color="group.visibility === 'PUBLIC' ? 'var(--ok)' : 'var(--fg-3)'">{{ group.visibility }}</Badge>
                  </div>
                  <div class="meta-item">
                    <span class="meta-label">Members</span>
                    <span class="meta-value mono tabular">{{ members.length }}</span>
                  </div>
                </div>
              </div>
            </SectionCard>

            <SectionCard v-if="group.description" title="Description">
              <div class="card-body">
                <p class="description-text">{{ group.description }}</p>
              </div>
            </SectionCard>
          </div>
        </div>
      </div>

      <!-- ── Prayers tab ─────────────────────────────────── -->
      <div v-if="activeTab === 'Prayers'" class="tab-content">
        <SectionCard :title="`Prayers · ${prayers.length}`">
          <template #right>
            <Button
              size="sm"
              icon="plus"
              :accent="accent"
              @click="addPrayerModalOpen = true"
            >
              Add Prayer
            </Button>
          </template>
          <div class="card-body">
            <div v-if="!prayers.length" class="empty-state">No prayers</div>
            <div v-else class="prayer-list">
              <div v-for="p in prayers" :key="p.id" class="prayer-row">
                <div class="prayer-header">
                  <Avatar :name="p.profile.name" :size="24" />
                  <NuxtLink :to="`/audience/profiles/${p.profile.id}`" class="prayer-author">
                    {{ p.profile.name }}
                  </NuxtLink>
                  <Badge :color="p.status === 'ACTIVE' ? 'var(--ok)' : p.status === 'ANSWERED' ? accent : 'var(--fg-3)'">
                    {{ p.status }}
                  </Badge>
                  <span class="spacer" />
                  <span class="prayer-date">{{ formatDate(p.created) }}</span>
                  <button class="remove-btn" @click="onDeletePrayer(p.id)">
                    <Icon name="trash" :size="12" color="var(--fg-3)" />
                  </button>
                </div>
                <div class="prayer-title">{{ p.title }}</div>
                <p class="prayer-content">{{ prayerText(p.content) }}</p>
              </div>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- ── Activities tab ───────────────────────────────── -->
      <div v-if="activeTab === 'Activities'" class="tab-content">
        <SectionCard :title="`Activities · ${activities.length}`">
          <div class="card-body">
            <div v-if="!activities.length" class="empty-state">No activities</div>
            <div v-else class="item-list">
              <div v-for="a in activities" :key="a.id" class="activity-row">
                <div class="activity-info">
                  <span class="item-primary">{{ a.name }}</span>
                  <span class="item-secondary">{{ a.description }}</span>
                </div>
                <Badge :color="accent">{{ a.type }}</Badge>
              </div>
            </div>
          </div>
        </SectionCard>
      </div>

      <!-- ── Signup tab ───────────────────────────────────── -->
      <div v-if="activeTab === 'Signup'" class="tab-content">
        <SectionCard title="Signup Tokens">
          <template #right>
            <Button size="sm" icon="plus" @click="onAddToken">Generate Token</Button>
          </template>
          <div class="card-body">
            <div v-if="!tokens.length" class="empty-state">No signup tokens</div>
            <div v-else class="token-list">
              <div v-for="t in tokens" :key="t.token" class="token-row">
                <div class="token-header">
                  <span class="token-dates">
                    Created {{ formatDate(t.created) }} · Expires {{ formatDate(t.expires) }}
                  </span>
                  <span class="spacer" />
                  <button class="copy-btn" @click="copyToClipboard(t.token)">
                    <Icon name="copy" :size="12" color="var(--fg-3)" />
                  </button>
                  <button class="remove-btn" @click="onDeleteToken(t.token)">
                    <Icon name="trash" :size="12" color="var(--fg-3)" />
                  </button>
                </div>
                <div class="token-value mono">{{ t.token }}</div>
              </div>
            </div>
          </div>
        </SectionCard>
      </div>
      <!-- ── Settings tab ──────────────────────────────── -->
      <div v-if="activeTab === 'Settings'" class="tab-content">
        <SectionCard title="Group Settings">
          <div class="card-body settings-form">
            <TextInput v-model="editGroup.name" label="Name" placeholder="Group name" />
            <TextInput v-model="editGroup.description" label="Description" placeholder="Group description" />
            <Select
              v-model="editGroup.type"
              label="Type"
              :options="[
                { value: 'SMALL_GROUP', label: 'Small Group' },
                { value: 'FAMILY', label: 'Family' },
                { value: 'CUSTOM', label: 'Custom' },
              ]"
            />
            <Select
              v-model="editGroup.visibility"
              label="Visibility"
              :options="[
                { value: 'PUBLIC', label: 'Public' },
                { value: 'PRIVATE', label: 'Private' },
                { value: 'HIDDEN', label: 'Hidden' },
              ]"
            />
            <div class="settings-actions">
              <Button
                size="sm"
                primary
                :accent="accent"
                :disabled="!editDirty || saving"
                @click="onSaveGroup"
              >
                {{ saving ? 'Saving…' : 'Save Changes' }}
              </Button>
            </div>
          </div>
        </SectionCard>
      </div>
    </template>

    <div v-else class="empty-centered">
      <template v-if="error">
        <p>Failed to load community group.</p>
        <p class="error-detail">{{ error.message }}</p>
      </template>
      <template v-else>
        Community group not found.
      </template>
    </div>

    <!-- Add Member Modal -->
    <Modal
      v-if="addMemberModalOpen"
      title="Add Member"
      icon="user-plus"
      :accent="accent"
      @close="addMemberModalOpen = false"
    >
      <TextInput v-model="newProfileId" label="Profile ID" placeholder="Enter profile UUID" />
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="addMemberModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newProfileId.trim()"
          @click="onAddMember"
        >
          Add Member
        </Button>
      </template>
    </Modal>

    <!-- Add Prayer Modal -->
    <Modal
      v-if="addPrayerModalOpen"
      title="Add Prayer"
      icon="heart"
      :accent="accent"
      @close="addPrayerModalOpen = false"
    >
      <TextInput v-model="newPrayerTitle" label="Title" placeholder="Prayer title" />
      <TextInput v-model="newPrayerContent" label="Content" placeholder="Enter prayer request…" />
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="addPrayerModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!newPrayerTitle.trim()"
          @click="onAddPrayer"
        >
          Add Prayer
        </Button>
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

.tab-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
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

.error-detail {
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--err);
}

.description-text {
  font-size: 13px;
  color: var(--fg-1);
  line-height: 1.5;
  margin: 0;
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

/* ── Members ── */
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
  transition: background 0.1s;
}

.member-row:hover {
  background: var(--bg-2);
}

.member-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
  text-decoration: none;
}

.member-name:hover {
  text-decoration: underline;
}

/* ── Item list ── */
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

/* ── Activities ── */
.activity-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  transition: background 0.1s;
}

.activity-row:hover {
  background: var(--bg-2);
}

.activity-info {
  flex: 1;
  min-width: 0;
}

/* ── Prayers ── */
.prayer-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.prayer-row {
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--bg-2);
  border: 1px solid var(--line);
}

.prayer-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.prayer-author {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--fg-0);
  text-decoration: none;
}

.prayer-author:hover {
  text-decoration: underline;
}

.prayer-date {
  font-size: 11px;
  color: var(--fg-3);
}

.prayer-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-0);
  margin-bottom: 4px;
}

.prayer-content {
  font-size: 13px;
  color: var(--fg-1);
  line-height: 1.5;
  margin: 0;
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

.token-dates {
  font-size: 11.5px;
  color: var(--fg-3);
}

.token-value {
  font-size: 11px;
  color: var(--fg-2);
  word-break: break-all;
  line-height: 1.5;
}

/* ── Buttons ── */
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

/* ── Settings ── */
.settings-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 480px;
}

.settings-actions {
  padding-top: 4px;
}
</style>
