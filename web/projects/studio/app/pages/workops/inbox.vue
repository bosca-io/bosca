<script setup lang="ts">
import gql from 'graphql-tag'
import type { MatrixPreference } from '~/components/NotificationPreferenceMatrix.vue'
import {
  setWorkOpsChannelEnabled,
  WORKOPS_NOTIFICATION_CHANNELS,
  WORKOPS_NOTIFICATION_TYPES,
  workOpsMatrixPreferences,
} from '~/utils/workopsNotificationPreferences'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query, mutation } = useGraphQL()
const toast = useToast()

const activeTab = ref('Unread')
const offset = ref(0)
const limit = ref(30)

const EVENT_STYLES: Record<string, { icon: string; color: string }> = {
  ASSIGNED: { icon: 'user', color: '#5ec5ff' },
  MENTIONED: { icon: 'message', color: '#a78bff' },
  STATUS_CHANGED: { icon: 'workflow', color: '#ffb547' },
  COMMENTED: { icon: 'message', color: '#5ec5ff' },
  RESOLVED: { icon: 'check', color: '#34d99a' },
  CLOSED: { icon: 'check', color: '#34d99a' },
  BREACHED: { icon: 'alert', color: '#ff5d6c' },
  AT_RISK: { icon: 'alert', color: '#ffb547' },
  DUE: { icon: 'calendar', color: '#ffb547' },
  WATCHED: { icon: 'eye', color: '#6c7388' },
  PIPELINE_APPROVAL_REQUESTED: { icon: 'shield-question', color: '#ffb547' },
}

const unreadGql = gql`
  query UnreadNotifications($limit: Int!, $offset: Long!) {
    workOps {
      notifications {
        unreadCount
        unread(limit: $limit, offset: $offset) {
          id
          event
          body
          link
          actorProfileId
          readAt
          createdAt
        }
      }
    }
  }
`

const allGql = gql`
  query AllNotifications($limit: Int!, $offset: Long!) {
    workOps {
      notifications {
        mine(limit: $limit, offset: $offset) {
          id
          event
          body
          link
          actorProfileId
          readAt
          createdAt
        }
      }
    }
  }
`

interface Notification {
  id: string
  event: string
  body: string
  link: string | null
  actorProfileId: string | null
  readAt: string | null
  createdAt: string
}

const { data: unreadData, status: unreadStatus, refresh: refreshUnread } = useAsyncQuery<{
  workOps: { notifications: { unread: Notification[]; unreadCount: number } }
}>('workops-inbox-unread', unreadGql, { limit, offset }, { server: false })

const { data: allData, status: allStatus, refresh: refreshAll } = useAsyncQuery<{
  workOps: { notifications: { mine: Notification[] } }
}>('workops-inbox-all', allGql, { limit, offset }, { server: false })

const notifications = computed(() => {
  if (activeTab.value === 'Unread') return unreadData.value?.workOps?.notifications?.unread ?? []
  return allData.value?.workOps?.notifications?.mine ?? []
})

const unreadCount = computed(() => unreadData.value?.workOps?.notifications?.unreadCount ?? 0)

// 'idle' counts as loading: these queries are client-only (`server: false`),
// so during SSR the status is 'idle' with no data — rendering the empty state
// there would mismatch the client's initial 'pending' render (hydration error).
const isLoading = computed(() => {
  if (activeTab.value === 'Preferences') return false
  const status = activeTab.value === 'Unread' ? unreadStatus.value : allStatus.value
  return status === 'pending' || status === 'idle'
})

function relativeTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(ms / 60_000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  const days = Math.floor(hrs / 24)
  return `${days}d ago`
}

function eventStyle(event: string) {
  return EVENT_STYLES[event] || { icon: 'bell', color: '#6c7388' }
}

async function markRead(id: string) {
  await mutation(gql`
    mutation MarkRead($id: UUID!) {
      workOps { notifications { markRead(id: $id) } }
    }
  `, { id })
  await refreshUnread()
  await refreshAll()
}

async function markAllRead() {
  await mutation(gql`
    mutation MarkAllRead {
      workOps { notifications { markAllRead } }
    }
  `, {})
  await refreshUnread()
  await refreshAll()
}

function onNotificationClick(n: Notification) {
  if (!n.readAt) markRead(n.id)
  if (n.link) navigateTo(n.link)
}

// ─── Preferences ─────────────────────────────────────────────────────────────
interface NotifPrefs {
  dailyDigest: boolean
  watchAuthored: boolean
  watchCommented: boolean
  dndStartLocal: string
  dndEndLocal: string
  eventChannels: Record<string, string[]>
  mutedProjectIds: string[]
  mutedTaskIds: string[]
  version: number
}

const prefs = ref<NotifPrefs | null>(null)
const prefsSaving = ref(false)
const prefsError = ref(false)
const notificationChannels = [...WORKOPS_NOTIFICATION_CHANNELS]
const channelPreferences = computed<MatrixPreference[]>(() =>
  workOpsMatrixPreferences(prefs.value?.eventChannels ?? {}),
)

function onChannelToggle(channel: string, event: string, optedOut: boolean) {
  if (!prefs.value) return
  prefs.value.eventChannels = setWorkOpsChannelEnabled(
    prefs.value.eventChannels,
    event,
    channel,
    !optedOut,
  )
}

// Mirrors the backend defaults in NotificationPreference: `myPreferences`
// returns null for a profile that has never customized, in which case the
// default (in-app and email, watch own, no digest, no DND) applies.
function defaultPrefs(): NotifPrefs {
  return {
    dailyDigest: false,
    watchAuthored: true,
    watchCommented: true,
    dndStartLocal: '',
    dndEndLocal: '',
    eventChannels: {},
    mutedProjectIds: [],
    mutedTaskIds: [],
    version: 0,
  }
}

async function loadPrefs() {
  prefsError.value = false
  try {
    const result = await query<{
      workOps: { notifications: { myPreferences: NotifPrefs | null } }
    }>(gql`
      query GetNotifPrefs {
        workOps { notifications { myPreferences {
          dailyDigest watchAuthored watchCommented
          dndStartLocal dndEndLocal eventChannels
          mutedProjectIds mutedTaskIds version
        } } }
      }
    `, {})
    const existing = result.workOps?.notifications?.myPreferences
    prefs.value = existing
      ? { ...existing, dndStartLocal: existing.dndStartLocal ?? '', dndEndLocal: existing.dndEndLocal ?? '' }
      : defaultPrefs()
  } catch {
    prefsError.value = true
    toast.error('Failed to load notification preferences')
  }
}

async function savePrefs() {
  if (!prefs.value) return
  prefsSaving.value = true
  try {
    await mutation(gql`
      mutation UpdateNotifPrefs($input: WorkOpsNotificationPreferenceUpdateInput!) {
        workOps { notifications { updatePreferences(input: $input) { version } } }
      }
    `, {
      input: {
        dailyDigest: prefs.value.dailyDigest,
        watchAuthored: prefs.value.watchAuthored,
        watchCommented: prefs.value.watchCommented,
        dndStartLocal: prefs.value.dndStartLocal || null,
        dndEndLocal: prefs.value.dndEndLocal || null,
        eventChannels: prefs.value.eventChannels,
        mutedProjectIds: prefs.value.mutedProjectIds,
        mutedTaskIds: prefs.value.mutedTaskIds,
      },
    })
    toast.success('Preferences saved')
    await loadPrefs()
  } catch {
    toast.error('Failed to save preferences')
  } finally {
    prefsSaving.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'Preferences' && !prefs.value) loadPrefs()
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Inbox')"
        title="Inbox"
        :subtitle="`${unreadCount} unread`"
        :tabs="['Unread', 'All', 'Preferences']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      >
        <template #actions>
          <Button size="sm" :disabled="unreadCount === 0" @click="markAllRead">Mark All Read</Button>
        </template>
      </PageHeader>
    </template>

    <template v-if="activeTab !== 'Preferences'">
      <div v-if="isLoading" class="loading-state">Loading notifications…</div>

      <div v-else-if="notifications.length" class="notification-list">
        <div
          v-for="n in notifications"
          :key="n.id"
          :class="['notification-item', { unread: !n.readAt }]"
          @click="onNotificationClick(n)"
        >
          <div class="notif-icon" :style="{ background: `color-mix(in oklch, ${eventStyle(n.event).color} 18%, transparent)` }">
            <Icon :name="eventStyle(n.event).icon" :size="14" :color="eventStyle(n.event).color" />
          </div>
          <div class="notif-content">
            <span class="notif-body">{{ n.body }}</span>
            <span class="notif-time">{{ relativeTime(n.createdAt) }}</span>
          </div>
          <span v-if="!n.readAt" class="unread-dot" />
        </div>
      </div>

      <div v-else class="empty-state">
        {{ activeTab === 'Unread' ? 'All caught up!' : 'No notifications yet.' }}
      </div>
    </template>

    <!-- Preferences Panel -->
    <div v-else class="prefs-panel">
      <div v-if="prefsError" class="empty-state">
        Couldn’t load preferences.
        <Button size="sm" @click="loadPrefs">Retry</Button>
      </div>
      <div v-else-if="!prefs" class="loading-state">Loading preferences…</div>
      <template v-else>
        <SectionCard title="Watch Settings" subtitle="Automatically watch tasks you interact with." padded>
          <div class="prefs-row">
            <Switch v-model="prefs.watchAuthored" label="Watch tasks I create" />
          </div>
          <div class="prefs-row">
            <Switch v-model="prefs.watchCommented" label="Watch tasks I comment on" />
          </div>
        </SectionCard>

        <SectionCard
          title="Delivery Channels"
          subtitle="Turn each supported channel on or off for every kind of Work Ops activity."
        >
          <div class="channel-matrix">
            <NotificationPreferenceMatrix
              :types="WORKOPS_NOTIFICATION_TYPES"
              :preferences="channelPreferences"
              :channels="notificationChannels"
              :accent="accent"
              :disabled="prefsSaving"
              @toggle="onChannelToggle"
            />
          </div>
          <p class="channel-help">
            In-app and email are on by default. Webhook and Slack require a configured delivery pipeline.
            Push delivery is not currently available for Work Ops notifications.
          </p>
        </SectionCard>

        <SectionCard title="Digest" subtitle="Receive a daily summary of activity." padded>
          <div class="prefs-row">
            <Switch v-model="prefs.dailyDigest" label="Daily digest email" />
          </div>
        </SectionCard>

        <SectionCard title="Do Not Disturb" subtitle="Suppress notifications during specific hours (local time)." padded>
          <div class="prefs-dnd-row">
            <TextInput
              v-model="prefs.dndStartLocal"
              label="Start"
              placeholder="22:00"
              class="dnd-input" />
            <span class="dnd-sep">to</span>
            <TextInput
              v-model="prefs.dndEndLocal"
              label="End"
              placeholder="08:00"
              class="dnd-input" />
          </div>
        </SectionCard>

        <div class="prefs-actions">
          <Button
            primary
            :accent="accent"
            :disabled="prefsSaving"
            @click="savePrefs">Save Preferences</Button>
        </div>
      </template>
    </div>
  </PageShell>
</template>

<style scoped>
.loading-state,
.empty-state {
  color: var(--fg-3);
  text-align: center;
  padding: 60px 0;
  font-size: 14px;
}

.notification-list {
  display: flex;
  flex-direction: column;
}

.notification-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  transition: background 0.1s;
}

.notification-item:hover {
  background: var(--bg-1);
}

.notification-item.unread {
  background: color-mix(in oklch, v-bind(accent) 4%, transparent);
}

.notif-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 32px;
}

.notif-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.notif-body {
  font-size: 13px;
  color: var(--fg-0);
  line-height: 1.4;
}

.notif-time {
  font-size: 11px;
  color: var(--fg-3);
}

.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: v-bind(accent);
  flex: 0 0 8px;
}

/* Preferences */
.prefs-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
  max-width: 960px;
}

.prefs-row {
  padding: 8px 0;
}

.prefs-dnd-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.dnd-input {
  width: 100px;
}

.dnd-sep {
  font-size: 13px;
  color: var(--fg-3);
  padding-bottom: 8px;
}

.prefs-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.channel-matrix {
  overflow-x: auto;
}

.channel-help {
  margin: 0;
  padding: 10px 16px 14px;
  border-top: 1px solid var(--line);
  color: var(--fg-3);
  font-size: 12px;
  line-height: 1.5;
}
</style>
