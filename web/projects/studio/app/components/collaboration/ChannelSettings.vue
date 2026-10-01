<script lang="ts" setup>
/* eslint-disable @typescript-eslint/no-explicit-any */
import { useProfileSearch } from '~/composables/useProfileSearch'

interface ChannelMember {
  profileId: string
  name: string
  role: string
}

const props = defineProps<{
  channelId: string
  channelName: string
}>()

const emit = defineEmits<{
  close: []
}>()

const gql = useGraphQL()
const toast = useToast()
const { searchProfiles } = useProfileSearch()

const members = ref<ChannelMember[]>([])
const loading = ref(true)
const removing = ref<string | null>(null)
const showAddMember = ref(false)
const selectedProfileId = ref<string | null>(null)
const selectedProfileName = ref('')
const adding = ref(false)

const GET_CHANNEL = `
  query GetChannelMembers($id: UUID!) {
    chat { channel(id: $id) { members { profileId role profile { id name slug } } } }
  }
`

const INVITE_TO_CHANNEL = `
  mutation InviteToChannel($channelId: UUID!, $profileId: UUID!) {
    chat { inviteToChannel(channelId: $channelId, inviteeProfileId: $profileId) { id } }
  }
`

const REMOVE_CHANNEL_MEMBER = `
  mutation RemoveChannelMember($channelId: UUID!, $profileId: UUID!) {
    chat { removeChannelMember(channelId: $channelId, profileId: $profileId) }
  }
`

async function loadMembers() {
  loading.value = true
  try {
    const result = await gql.query<any>(GET_CHANNEL, { id: props.channelId })
    const raw = result?.chat?.channel?.members ?? []
    members.value = raw.map((m: any) => ({
      profileId: m.profileId,
      name: m.profile?.name || m.profile?.slug || m.profileId,
      role: m.role || 'member',
    }))
  } catch {
    members.value = []
  }
  loading.value = false
}

function onProfileSelected(profileId: string | string[] | null | undefined) {
  if (typeof profileId !== 'string') return
  if (!profileId || members.value.some(m => m.profileId === profileId)) {
    toast.error('Already a member')
    return
  }
  selectedProfileId.value = profileId
  selectedProfileName.value = lastSearchResults.value.find(r => r.value === profileId)?.label || profileId
}

async function onConfirmAdd() {
  if (!selectedProfileId.value) return
  adding.value = true
  try {
    await gql.mutation(INVITE_TO_CHANNEL, { channelId: props.channelId, profileId: selectedProfileId.value })
    toast.success('Invitation sent')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to send invitation')
  }
  adding.value = false
  selectedProfileId.value = null
  selectedProfileName.value = ''
  showAddMember.value = false
  loadMembers()
}

async function onRemoveMember(profileId: string) {
  removing.value = profileId
  try {
    await gql.mutation(REMOVE_CHANNEL_MEMBER, { channelId: props.channelId, profileId })
    members.value = members.value.filter(m => m.profileId !== profileId)
    toast.success('Member removed')
  } catch (e: any) {
    toast.error(e?.message || 'Failed to remove member')
  }
  removing.value = null
}

const lastSearchResults = ref<{ value: string; label: string }[]>([])

async function onProfileSearch(query: string) {
  const results = await searchProfiles(query)
  lastSearchResults.value = results
  return results
}

onMounted(() => loadMembers())
</script>

<template>
  <Teleport to="body">
    <div class="settings-overlay" @click.self="emit('close')">
      <div class="settings-dialog">
        <div class="settings-header">
          <h3 class="settings-title"># {{ channelName }}</h3>
          <button class="settings-close" @click="emit('close')">
            <Icon name="x" :size="16" />
          </button>
        </div>

        <div class="settings-body">
          <!-- Members section -->
          <div class="section-label">Members ({{ members.length }})</div>

          <div v-if="loading" class="loading-text">Loading members…</div>

          <div v-else class="member-list">
            <div v-for="m in members" :key="m.profileId" class="member-row">
              <div class="member-avatar">{{ m.name.charAt(0).toUpperCase() }}</div>
              <span class="member-name">{{ m.name }}</span>
              <span class="member-role">{{ m.role }}</span>
              <button
                class="member-remove"
                :disabled="removing === m.profileId"
                title="Remove"
                @click="onRemoveMember(m.profileId)"
              >
                <Icon name="x" :size="12" />
              </button>
            </div>
          </div>

          <!-- Invite member -->
          <div class="add-member-section">
            <button v-if="!showAddMember" class="add-member-btn" @click="showAddMember = true">
              <Icon name="plus" :size="14" /> Invite Member
            </button>
            <template v-else>
              <Select
                placeholder="Search for a person…"
                searchable
                :on-search="onProfileSearch"
                @update:model-value="onProfileSelected"
              />
              <div v-if="selectedProfileId" class="selected-person">
                <span class="selected-name">{{ selectedProfileName }}</span>
                <button class="confirm-add-btn" :disabled="adding" @click="onConfirmAdd">
                  {{ adding ? 'Sending…' : 'Send Invitation' }}
                </button>
              </div>
              <button class="add-member-cancel" @click="showAddMember = false; selectedProfileId = null">Cancel</button>
            </template>
          </div>

          <div class="section-sep" />

          <!-- Bridges section -->
          <BridgeAdmin :channel-id="channelId" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.settings-overlay {
  position: fixed; inset: 0; z-index: 9000;
  background: rgba(0, 0, 0, 0.5);
  display: flex; align-items: center; justify-content: center;
}

.settings-dialog {
  background: var(--bg-0); border: 1px solid var(--line);
  border-radius: var(--r-lg); width: 400px; max-height: 80vh;
  display: flex; flex-direction: column; overflow: hidden;
}

.settings-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 18px; border-bottom: 1px solid var(--line);
}

.settings-title { font-size: 15px; font-weight: 600; color: var(--fg-0); margin: 0; }

.settings-close {
  background: none; border: none; color: var(--fg-3); cursor: pointer;
  padding: 4px; border-radius: var(--r-sm);
}
.settings-close:hover { color: var(--fg-0); background: var(--bg-2); }

.settings-body {
  padding: 18px; overflow-y: auto;
}

.section-label {
  font-size: 11px; font-weight: 600; color: var(--fg-3);
  text-transform: uppercase; letter-spacing: 0.03em; margin-bottom: 8px;
}

.loading-text { font-size: 12px; color: var(--fg-3); }

.member-list {
  display: flex; flex-direction: column; gap: 2px;
}

.member-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 8px; border-radius: var(--r-sm);
}

.member-row:hover { background: var(--bg-2); }

.member-avatar {
  width: 24px; height: 24px; border-radius: 50%;
  background: var(--brand-2); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 600; flex-shrink: 0;
}

.member-name { flex: 1; font-size: 13px; color: var(--fg-0); font-weight: 500; }
.member-role { font-size: 11px; color: var(--fg-3); }

.member-remove {
  width: 22px; height: 22px; background: none; border: none;
  color: var(--fg-3); cursor: pointer; border-radius: var(--r-sm);
  display: flex; align-items: center; justify-content: center; opacity: 0;
}

.member-row:hover .member-remove { opacity: 1; }
.member-remove:hover { color: var(--err); background: var(--bg-3); }
.member-remove:disabled { opacity: 0.3; cursor: not-allowed; }

.add-member-section { margin-top: 14px; display: flex; flex-direction: column; gap: 8px; }

.add-member-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 12px; font-size: 13px; font-weight: 500;
  background: var(--bg-2); border: 1px solid var(--line);
  border-radius: var(--r-sm); color: var(--fg-1); cursor: pointer;
  width: 100%; justify-content: center;
}
.add-member-btn:hover { background: var(--bg-3); border-color: var(--brand-2); }

.add-member-cancel {
  font-size: 12px; color: var(--fg-3); background: none; border: none;
  cursor: pointer; align-self: flex-end;
}
.add-member-cancel:hover { color: var(--fg-1); }

.selected-person {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 10px; background: var(--bg-2); border-radius: var(--r-sm);
  border: 1px solid var(--line);
}

.selected-name { flex: 1; font-size: 13px; font-weight: 500; color: var(--fg-0); }

.confirm-add-btn {
  padding: 5px 12px; font-size: 12px; font-weight: 500;
  background: var(--brand-2); color: #fff; border: none;
  border-radius: var(--r-sm); cursor: pointer;
}
.confirm-add-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.confirm-add-btn:not(:disabled):hover { filter: brightness(1.15); }

.section-sep {
  height: 1px; background: var(--line); margin: 16px 0;
}
</style>
