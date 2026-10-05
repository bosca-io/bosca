<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
import { WORKOPS_NOTIFICATION_EVENTS } from '~/utils/workopsNotificationPreferences'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

// ── Queries ──────────────────────────────────────────────────────────

const schemesGql = gql`
  query NotificationSchemes {
    workOps { notifications {
      schemes { id name description eventRecipients version }
      myPreferences {
        profileId watchAuthored watchCommented dailyDigest
        dndStartLocal dndEndLocal eventChannels
        mutedTaskIds mutedProjectIds version
      }
    } }
  }
`

// ── Data fetching ────────────────────────────────────────────────────

interface NotificationScheme { id: string; name: string; description: string | null; eventRecipients: Record<string, Array<{ type: string }>>; version: number }
interface NotificationPreferences { profileId: string; watchAuthored: boolean; watchCommented: boolean; dailyDigest: boolean; dndStartLocal: string | null; dndEndLocal: string | null; eventChannels: Record<string, unknown>; mutedTaskIds: string[]; mutedProjectIds: string[]; version: number }

const { data, status, refresh } = useAsyncQuery<{
  workOps: { notifications: { schemes: NotificationScheme[]; myPreferences: NotificationPreferences | null } }
}>('workops-notifications', schemesGql, {})

const schemes = computed(() => data.value?.workOps?.notifications?.schemes ?? [])
const myPrefs = computed(() => data.value?.workOps?.notifications?.myPreferences ?? null)
const isLoading = computed(() => status.value === 'pending')

const selectedSchemeId = ref<string | null>(null)

watch(schemes, (s) => {
  if (!selectedSchemeId.value && s.length > 0 && s[0]) {
    selectedSchemeId.value = s[0].id
  }
}, { immediate: true })

const activeScheme = computed(() => schemes.value.find((s) => s.id === selectedSchemeId.value) ?? null)

const eventColumns: GlassTableColumn[] = [
  { key: 'event', label: 'Event', width: 'minmax(180px, 1fr)' },
  { key: 'recipients', label: 'Recipients', width: '2fr' },
]

const eventRows = computed(() =>
  WORKOPS_NOTIFICATION_EVENTS.map(event => ({
    event,
    recipients: activeScheme.value?.eventRecipients?.[event] ?? [],
  })),
)

// ── Edit Preferences ────────────────────────────────────────────────

const showEditPrefs = ref(false)
const prefForm = reactive({
  watchAuthored: true,
  watchCommented: true,
  dailyDigest: false,
  dndStart: '',
  dndEnd: '',
})
const savingPrefs = ref(false)

function openEditPrefs() {
  const p = myPrefs.value
  prefForm.watchAuthored = p?.watchAuthored ?? true
  prefForm.watchCommented = p?.watchCommented ?? true
  prefForm.dailyDigest = p?.dailyDigest ?? false
  prefForm.dndStart = p?.dndStartLocal ?? ''
  prefForm.dndEnd = p?.dndEndLocal ?? ''
  showEditPrefs.value = true
}

async function savePrefs() {
  savingPrefs.value = true
  try {
    await gqlMutation(
      gql`mutation UpdateNotificationPreferences($input: WorkOpsNotificationPreferenceUpdateInput!) {
        workOps { notifications {
          updatePreferences(input: $input) { profileId version }
        } }
      }`,
      {
        input: {
          eventChannels: myPrefs.value?.eventChannels ?? {},
          watchAuthored: prefForm.watchAuthored,
          watchCommented: prefForm.watchCommented,
          dailyDigest: prefForm.dailyDigest,
          dndStartLocal: prefForm.dndStart || null,
          dndEndLocal: prefForm.dndEnd || null,
          mutedTaskIds: myPrefs.value?.mutedTaskIds ?? [],
          mutedProjectIds: myPrefs.value?.mutedProjectIds ?? [],
        },
      },
    )
    showEditPrefs.value = false
    toast.success('Preferences updated')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update preferences')
  } finally {
    savingPrefs.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Work Ops', 'Settings', 'Notifications')"
        title="Notifications"
        subtitle="Notification schemes and personal preferences"
      />
    </template>

    <div v-if="isLoading" class="loading-state">Loading...</div>

    <template v-else>
      <div class="notifications-layout">
        <!-- Sidebar: schemes + prefs -->
        <div class="notif-sidebar">
          <SectionCard title="Schemes">
            <div class="scheme-list">
              <button
                v-for="s in schemes"
                :key="s.id"
                class="scheme-item"
                :class="{ active: selectedSchemeId === s.id }"
                @click="selectedSchemeId = s.id"
              >
                <span class="scheme-name">{{ s.name }}</span>
                <span v-if="s.description" class="scheme-desc">{{ s.description }}</span>
              </button>
              <div v-if="!schemes.length" class="empty-state">No schemes configured.</div>
            </div>
          </SectionCard>

          <SectionCard title="My Preferences">
            <template #right>
              <Button size="sm" icon="pencil" @click="openEditPrefs">Edit</Button>
            </template>
            <div v-if="myPrefs" class="prefs-body">
              <div class="pref-row">
                <span class="pref-label">Auto-watch authored</span>
                <Badge :color="myPrefs.watchAuthored ? '#4ade80' : 'var(--fg-3)'">
                  {{ myPrefs.watchAuthored ? 'On' : 'Off' }}
                </Badge>
              </div>
              <div class="pref-row">
                <span class="pref-label">Auto-watch commented</span>
                <Badge :color="myPrefs.watchCommented ? '#4ade80' : 'var(--fg-3)'">
                  {{ myPrefs.watchCommented ? 'On' : 'Off' }}
                </Badge>
              </div>
              <div class="pref-row">
                <span class="pref-label">Daily digest</span>
                <Badge :color="myPrefs.dailyDigest ? '#4ade80' : 'var(--fg-3)'">
                  {{ myPrefs.dailyDigest ? 'On' : 'Off' }}
                </Badge>
              </div>
              <div v-if="myPrefs.dndStartLocal" class="pref-row">
                <span class="pref-label">Do not disturb</span>
                <span class="pref-value mono">{{ myPrefs.dndStartLocal }} - {{ myPrefs.dndEndLocal }}</span>
              </div>
            </div>
            <div v-else class="prefs-body empty-state">Using defaults (in-app and email).</div>
          </SectionCard>
        </div>

        <!-- Main: event recipients table -->
        <div class="notif-main">
          <SectionCard v-if="activeScheme" :title="activeScheme.name" :subtitle="activeScheme.description || undefined">
            <GlassTable
              :columns="eventColumns"
              :rows="eventRows"
              empty-text="No events configured."
            >
              <template #col-event="{ row }">
                <span class="event-name mono">{{ row.event }}</span>
              </template>
              <template #col-recipients="{ row }">
                <div v-if="row.recipients.length" class="recipients-list">
                  <Badge v-for="(r, i) in row.recipients" :key="i" color="var(--fg-3)">
                    {{ r.type }}
                  </Badge>
                </div>
                <span v-else class="empty-state">none</span>
              </template>
            </GlassTable>
          </SectionCard>
          <div v-else class="empty-centered">Select a scheme to view its event recipients.</div>
        </div>
      </div>
    </template>

    <!-- Edit Preferences Modal -->
    <Modal
      v-if="showEditPrefs"
      title="Notification Preferences"
      icon="inbox"
      :accent="accent"
      @close="showEditPrefs = false">
      <div class="form-stack">
        <Switch v-model="prefForm.watchAuthored" label="Auto-watch tasks I create" />
        <Switch v-model="prefForm.watchCommented" label="Auto-watch tasks I comment on" />
        <Switch v-model="prefForm.dailyDigest" label="Daily digest email" />
        <TextInput
          v-model="prefForm.dndStart"
          label="Do not disturb start"
          type="time"
          placeholder="22:00" />
        <TextInput
          v-model="prefForm.dndEnd"
          label="Do not disturb end"
          type="time"
          placeholder="08:00" />
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showEditPrefs = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="savingPrefs"
          @click="savePrefs">
          {{ savingPrefs ? 'Saving...' : 'Save' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.loading-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.notifications-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 18px;
  align-items: start;
}

.notif-sidebar {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.notif-main {
  min-width: 0;
}

.scheme-list {
  display: flex;
  flex-direction: column;
}

.scheme-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 16px;
  border: none;
  background: none;
  text-align: left;
  cursor: pointer;
  border-left: 2px solid transparent;
  transition: background 0.1s, border-color 0.1s;
}

.scheme-item:hover {
  background: var(--bg-2);
}

.scheme-item.active {
  background: color-mix(in oklch, var(--brand-2) 8%, transparent);
  border-left-color: var(--brand-2);
}

.scheme-item + .scheme-item {
  border-top: 1px solid var(--line);
}

.scheme-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.scheme-desc {
  font-size: 11.5px;
  color: var(--fg-3);
}

.prefs-body {
  padding: 10px 16px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pref-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.pref-label {
  font-size: 12px;
  color: var(--fg-2);
}

.pref-value {
  font-size: 11px;
  color: var(--fg-1);
}

.event-name {
  font-size: 11.5px;
  color: var(--fg-1);
}

.recipients-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.empty-state {
  font-size: 12.5px;
  color: var(--fg-3);
  padding: 12px 16px;
}

.empty-centered {
  padding: 64px;
  text-align: center;
  font-size: 13px;
  color: var(--fg-3);
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.spacer {
  flex: 1;
}
</style>
