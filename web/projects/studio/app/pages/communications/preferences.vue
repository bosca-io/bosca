<script setup lang="ts">
import gql from 'graphql-tag'
import { useAuth } from '@bosca/auth-client-browser'
import type { GlassTableColumn } from '@bosca/ui'
import type { MatrixNotificationType, MatrixPreference } from '~/components/NotificationPreferenceMatrix.vue'

const { accent } = useCurrentSubsystem()
const { query: gqlQuery, mutation: gqlMutation, useAsyncQuery } = useGraphQL()
const { searchProfiles } = useProfileSearch()
const toast = useToast()

const authState = import.meta.client ? useAuth() : null
const currentProfileId = computed(() => authState?.profile.value?.id ?? null)

const activeTab = ref('Types')
const userTab = ref('Preferences')

// ── Notification type catalog ────────────────────────────────────────

interface AdminNotificationType extends MatrixNotificationType {
  updatedAt: string
}

interface NotificationPreferenceMapping {
  type: string
  channel: 'EMAIL' | 'PUSH'
  provider: string
  externalId: string
}

const typesGql = gql`
  query AdminNotificationTypes {
    communications {
      notificationTypes { key name description optional system defaultEmailEnabled defaultPushEnabled hidden displayOrder updatedAt }
      notificationPreferenceMappings { type channel provider externalId }
    }
  }
`

const { data: typesData, refresh: refreshTypes } = useAsyncQuery<{
  communications: {
    notificationTypes: AdminNotificationType[]
    notificationPreferenceMappings: NotificationPreferenceMapping[]
  }
}>('messaging-admin-notification-types', typesGql, {})

const types = computed(() => typesData.value?.communications?.notificationTypes ?? [])
const preferenceMappings = computed(() =>
  typesData.value?.communications?.notificationPreferenceMappings ?? [],
)

function preferenceMapping(type: string, channel: 'EMAIL' | 'PUSH') {
  return preferenceMappings.value.find(mapping => mapping.type === type && mapping.channel === channel)
}

const typeColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Type', width: 'minmax(200px, 1fr)' },
  { key: 'key', label: 'Key', width: '150px', muted: true },
  { key: 'displayOrder', label: 'Order', width: '70px', muted: true },
  { key: 'defaultEmail', label: 'Email default', width: '120px' },
  { key: 'defaultPush', label: 'Push default', width: '120px' },
  { key: 'integration', label: 'Preference source', width: '180px' },
  { key: 'flags', label: 'Access', width: '170px' },
  { key: 'actions', label: '', width: '130px' },
]

const showTypeModal = ref(false)
const editingKey = ref<string | null>(null)
const typeForm = reactive({
  key: '',
  name: '',
  description: '',
  optional: true,
  defaultEmailEnabled: true,
  defaultPushEnabled: true,
  hidden: false,
  displayOrder: '0',
  hubSpotEmailSubscriptionId: '',
})
const savingType = ref(false)
const typeToDelete = ref<AdminNotificationType | null>(null)
const deletingType = ref(false)

const editingType = computed(() => types.value.find(t => t.key === editingKey.value) ?? null)

function openCreateType() {
  editingKey.value = null
  typeForm.key = ''
  typeForm.name = ''
  typeForm.description = ''
  typeForm.optional = true
  typeForm.defaultEmailEnabled = true
  typeForm.defaultPushEnabled = true
  typeForm.hidden = false
  typeForm.displayOrder = String((types.value.at(-1)?.displayOrder ?? 0) + 10)
  typeForm.hubSpotEmailSubscriptionId = ''
  showTypeModal.value = true
}

function openEditType(type: AdminNotificationType) {
  editingKey.value = type.key
  typeForm.key = type.key
  typeForm.name = type.name
  typeForm.description = type.description ?? ''
  typeForm.optional = type.optional
  typeForm.defaultEmailEnabled = type.defaultEmailEnabled
  typeForm.defaultPushEnabled = type.defaultPushEnabled
  typeForm.hidden = type.hidden
  typeForm.displayOrder = String(type.displayOrder)
  const mapping = preferenceMapping(type.key, 'EMAIL')
  typeForm.hubSpotEmailSubscriptionId = mapping?.provider === 'hubspot' ? mapping.externalId : ''
  showTypeModal.value = true
}

async function saveType() {
  const order = Number.parseInt(typeForm.displayOrder, 10)
  if (!typeForm.key || !typeForm.name || Number.isNaN(order)) {
    toast.error('A key, a name, and a numeric order are required')
    return
  }
  const hubSpotSubscriptionId = typeForm.hubSpotEmailSubscriptionId.trim()
  if (hubSpotSubscriptionId && !/^\d+$/.test(hubSpotSubscriptionId)) {
    toast.error('The HubSpot subscription ID must be numeric')
    return
  }
  if (hubSpotSubscriptionId && !typeForm.optional) {
    toast.error('Only notification types users can opt out of can map to HubSpot')
    return
  }
  const existingEmailMapping = preferenceMapping(typeForm.key, 'EMAIL')
  savingType.value = true
  try {
    await gqlMutation(
      gql`mutation SetNotificationType($key: String!, $name: String!, $description: String, $optional: Boolean!, $defaultEmailEnabled: Boolean!, $defaultPushEnabled: Boolean!, $displayOrder: Int!, $hidden: Boolean!) {
        communications {
          setNotificationType(key: $key, name: $name, description: $description, optional: $optional, defaultEmailEnabled: $defaultEmailEnabled, defaultPushEnabled: $defaultPushEnabled, displayOrder: $displayOrder, hidden: $hidden) {
            key name description optional system defaultEmailEnabled defaultPushEnabled hidden displayOrder updatedAt
          }
        }
      }`,
      {
        key: typeForm.key,
        name: typeForm.name,
        description: typeForm.description || null,
        optional: typeForm.optional,
        defaultEmailEnabled: typeForm.optional ? typeForm.defaultEmailEnabled : true,
        defaultPushEnabled: typeForm.optional ? typeForm.defaultPushEnabled : true,
        displayOrder: order,
        hidden: typeForm.hidden,
      },
    )
    if (hubSpotSubscriptionId) {
      await gqlMutation(
        gql`mutation SetNotificationPreferenceMapping($type: String!, $externalId: String!) {
          communications {
            setNotificationPreferenceMapping(type: $type, channel: EMAIL, provider: "hubspot", externalId: $externalId) {
              type channel provider externalId
            }
          }
        }`,
        { type: typeForm.key, externalId: hubSpotSubscriptionId },
      )
    }
    else if (existingEmailMapping?.provider === 'hubspot') {
      await gqlMutation(
        gql`mutation DeleteNotificationPreferenceMapping($type: String!) {
          communications {
            deleteNotificationPreferenceMapping(type: $type, channel: EMAIL)
          }
        }`,
        { type: typeForm.key },
      )
    }
    showTypeModal.value = false
    toast.success(editingKey.value ? 'Type updated' : 'Type created')
    await refreshTypes()
    if (profileId.value) await loadProfile(profileId.value)
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to save type')
  }
  finally {
    savingType.value = false
  }
}

async function deleteType() {
  const type = typeToDelete.value
  if (!type) return
  deletingType.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteNotificationType($key: String!) { communications { deleteNotificationType(key: $key) } }`,
      { key: type.key },
    )
    typeToDelete.value = null
    toast.success('Type deleted')
    await refreshTypes()
    if (profileId.value) await loadProfile(profileId.value)
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete type')
  }
  finally {
    deletingType.value = false
  }
}

// ── Per-user state ───────────────────────────────────────────────────

interface NotificationSettings {
  timeZone: string | null
  dndStartLocal: string | null
  dndEndLocal: string | null
}

interface DeliveryEvent {
  messageId: string
  channel: string
  status: string
  providerEvent: string | null
  errorMessage: string | null
  createdAt: string
}

interface Device {
  id: string
  platform: string
  created: string
  lastCheckIn: string
  installationId: string | null
  pushTokens: Array<{ token: string }>
}

const profileId = ref('')
const profilePrincipalId = ref<string | null>(null)
const profilePrefs = ref<MatrixPreference[]>([])
const profileSettings = ref<NotificationSettings | null>(null)
const profileEvents = ref<DeliveryEvent[]>([])
const profileDevices = ref<Device[]>([])
const loadingProfile = ref(false)
const savingPreference = ref(false)

const isOwnProfile = computed(() => profileId.value !== '' && profileId.value === currentProfileId.value)

const profileGql = gql`
  query AdminProfileNotifications($profileId: UUID!) {
    communications {
      notificationPreferences(profileId: $profileId) { profileId channel type optedOut updatedAt }
      notificationSettings(profileId: $profileId) { timeZone dndStartLocal dndEndLocal }
      recipientEvents(recipientId: $profileId, offset: 0, limit: 25) {
        messageId channel status providerEvent errorMessage createdAt
      }
    }
    profiles {
      profile(id: $profileId) { principal { id } }
    }
  }
`

const devicesGql = gql`
  query AdminProfileDevices($principalId: UUID!) {
    devices {
      devices(principalId: $principalId) {
        id platform created lastCheckIn installationId pushTokens { token }
      }
    }
  }
`

async function loadProfile(id: string) {
  loadingProfile.value = true
  try {
    const data = await gqlQuery(profileGql, { profileId: id }) as {
      communications: {
        notificationPreferences: MatrixPreference[]
        notificationSettings: NotificationSettings | null
        recipientEvents: DeliveryEvent[]
      }
      profiles: { profile: { principal: { id: string } | null } | null }
    } | null
    profilePrefs.value = data?.communications?.notificationPreferences ?? []
    profileSettings.value = data?.communications?.notificationSettings ?? null
    profileEvents.value = data?.communications?.recipientEvents ?? []
    profilePrincipalId.value = data?.profiles?.profile?.principal?.id ?? null
    await loadDevices()
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to load notification state')
  }
  finally {
    loadingProfile.value = false
  }
}

async function loadDevices() {
  if (!profilePrincipalId.value) {
    profileDevices.value = []
    return
  }
  const deviceData = await gqlQuery(devicesGql, { principalId: profilePrincipalId.value }) as {
    devices: { devices: Device[] }
  } | null
  profileDevices.value = deviceData?.devices?.devices ?? []
}

async function onProfileSelected(id: string) {
  profileId.value = id
  userTab.value = 'Preferences'
  if (!id) {
    profilePrincipalId.value = null
    profilePrefs.value = []
    profileSettings.value = null
    profileEvents.value = []
    profileDevices.value = []
    return
  }
  await loadProfile(id)
}

async function onToggle(channel: string, type: string, optedOut: boolean) {
  savingPreference.value = true
  try {
    await gqlMutation(
      gql`mutation SetNotificationPreference($profileId: UUID!, $channel: DeliveryChannel!, $type: String!, $optedOut: Boolean!) {
        communications {
          setNotificationPreference(profileId: $profileId, channel: $channel, type: $type, optedOut: $optedOut) {
            profileId channel type optedOut updatedAt
          }
        }
      }`,
      { profileId: profileId.value, channel, type, optedOut },
    )
    await loadProfile(profileId.value)
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update preference')
  }
  finally {
    savingPreference.value = false
  }
}

// ── Quiet hours ──────────────────────────────────────────────────────

const showEditQuietHours = ref(false)
const savingQuietHours = ref(false)
const quietForm = reactive({ timeZone: '', dndStart: '', dndEnd: '' })

const timeZoneOptions = computed(() => {
  if (!import.meta.client) return []
  return Intl.supportedValuesOf('timeZone').map(z => ({ value: z, label: z }))
})

function openEditQuietHours() {
  const s = profileSettings.value
  quietForm.timeZone = s?.timeZone ?? (import.meta.client ? Intl.DateTimeFormat().resolvedOptions().timeZone : '')
  quietForm.dndStart = s?.dndStartLocal ?? ''
  quietForm.dndEnd = s?.dndEndLocal ?? ''
  showEditQuietHours.value = true
}

async function saveQuietHours(clear: boolean) {
  if (!clear && (!quietForm.timeZone || !quietForm.dndStart || !quietForm.dndEnd)) {
    toast.error('Provide a time zone and both times, or clear quiet hours')
    return
  }
  savingQuietHours.value = true
  try {
    await gqlMutation(
      gql`mutation SetNotificationSettings($profileId: UUID!, $timeZone: String, $dndStartLocal: String, $dndEndLocal: String) {
        communications {
          setNotificationSettings(profileId: $profileId, timeZone: $timeZone, dndStartLocal: $dndStartLocal, dndEndLocal: $dndEndLocal) {
            profileId timeZone dndStartLocal dndEndLocal
          }
        }
      }`,
      clear
        ? { profileId: profileId.value, timeZone: null, dndStartLocal: null, dndEndLocal: null }
        : { profileId: profileId.value, timeZone: quietForm.timeZone, dndStartLocal: quietForm.dndStart, dndEndLocal: quietForm.dndEnd },
    )
    showEditQuietHours.value = false
    toast.success(clear ? 'Quiet hours cleared' : 'Quiet hours updated')
    await loadProfile(profileId.value)
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update quiet hours')
  }
  finally {
    savingQuietHours.value = false
  }
}

// ── Devices ──────────────────────────────────────────────────────────

const STALE_AFTER_DAYS = 30

function isStale(device: Device): boolean {
  const last = new Date(device.lastCheckIn).getTime()
  if (Number.isNaN(last)) return false
  return Date.now() - last > STALE_AFTER_DAYS * 24 * 60 * 60 * 1000
}

const platformLabels: Record<string, string> = {
  IOS: 'iOS',
  ANDROID: 'Android',
  WEB: 'Web',
  DESKTOP: 'Desktop',
}

const deviceColumns = computed<GlassTableColumn[]>(() => [
  { key: 'platform', label: 'Device', width: 'minmax(160px, 1fr)' },
  { key: 'registered', label: 'Registered', width: '170px', muted: true },
  { key: 'lastCheckIn', label: 'Last Seen', width: '170px' },
  { key: 'pushTokens', label: 'Push', width: '110px' },
  ...(isOwnProfile.value ? [{ key: 'actions', label: '', width: '100px' }] : []),
])

const deviceToRevoke = ref<Device | null>(null)
const revoking = ref(false)

async function revokeDevice() {
  const device = deviceToRevoke.value
  if (!device) return
  revoking.value = true
  try {
    await gqlMutation(
      gql`mutation DeleteDevice($id: UUID!) { devices { delete(id: $id) } }`,
      { id: device.id },
    )
    deviceToRevoke.value = null
    toast.success('Device removed — it will no longer receive push notifications')
    await loadDevices()
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to remove device')
  }
  finally {
    revoking.value = false
  }
}

// ── Formatting ───────────────────────────────────────────────────────

const eventColumns: GlassTableColumn[] = [
  { key: 'createdAt', label: 'When', width: '170px', muted: true },
  { key: 'channel', label: 'Channel', width: '90px' },
  { key: 'status', label: 'Status', width: '110px' },
  { key: 'reason', label: 'Detail', width: 'minmax(160px, 1fr)' },
]

const reasonLabels: Record<string, string> = {
  suppressed_preference: 'Opted out by preference',
  suppressed_address: 'Address on suppression list',
  deferred_quiet_hours: 'Deferred by quiet hours',
}

const statusColors: Record<string, string> = {
  SENT: '#34d99a',
  DELIVERED: '#34d99a',
  OPENED: '#34d99a',
  CLICKED: '#34d99a',
  DEFERRED: '#fbbf24',
  DROPPED: '#f87171',
  BOUNCED: '#f87171',
  FAILED: '#f87171',
  SPAM_REPORT: '#f87171',
  UNSUBSCRIBED: '#fbbf24',
}

function formatDate(d: string) {
  if (!d) return '—'
  return new Date(d).toLocaleString()
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Messaging', 'Preferences')"
        title="Notification Preferences"
        subtitle="Manage the notification type catalog and user preferences"
        :tabs="['Types', 'Users']"
        :active-tab="activeTab"
        @tab="activeTab = $event"
      />
    </template>

    <!-- ── Types tab ─────────────────────────────────────────────── -->
    <SectionCard
      v-if="activeTab === 'Types'"
      title="Notification Types"
      subtitle="Configure which notifications users receive by default and may change per channel. Keys are referenced by sending code and cannot change."
    >
      <template #right>
        <Button
          size="sm"
          icon="plus"
          primary
          :accent="accent"
          @click="openCreateType">New Type</Button>
      </template>
      <GlassTable :columns="typeColumns" :rows="types" empty-text="No notification types defined.">
        <template #col-name="{ row }">
          <div class="type-cell">
            <span class="type-name">{{ row.name }}</span>
            <span v-if="row.description" class="type-desc">{{ row.description }}</span>
          </div>
        </template>
        <template #col-key="{ row }">
          <span class="mono type-key">{{ row.key }}</span>
        </template>
        <template #col-displayOrder="{ row }">{{ row.displayOrder }}</template>
        <template #col-integration="{ row }">
          <span v-if="preferenceMapping(row.key, 'EMAIL')" class="mapping-label mono">
            {{ preferenceMapping(row.key, 'EMAIL')?.provider }}:
            {{ preferenceMapping(row.key, 'EMAIL')?.externalId }}
          </span>
          <span v-else class="muted">Bosca</span>
        </template>
        <template #col-defaultEmail="{ row }">
          <Badge v-if="!row.optional" color="var(--fg-3)">Always on</Badge>
          <Badge v-else :color="row.defaultEmailEnabled ? '#34d99a' : '#f87171'">
            {{ row.defaultEmailEnabled ? 'Enabled' : 'Disabled' }}
          </Badge>
        </template>
        <template #col-defaultPush="{ row }">
          <Badge v-if="!row.optional" color="var(--fg-3)">Always on</Badge>
          <Badge v-else :color="row.defaultPushEnabled ? '#34d99a' : '#f87171'">
            {{ row.defaultPushEnabled ? 'Enabled' : 'Disabled' }}
          </Badge>
        </template>
        <template #col-flags="{ row }">
          <div class="flag-list">
            <Badge v-if="row.system" color="var(--fg-3)">System</Badge>
            <Badge v-if="row.hidden" color="#a78bfa">Hidden</Badge>
            <Badge v-if="!row.optional" color="#fbbf24">Always on</Badge>
            <Badge v-else color="#34d99a">Optional</Badge>
          </div>
        </template>
        <template #col-actions="{ row }">
          <div class="action-list">
            <Button size="xs" icon="pencil" @click="openEditType(row)">Edit</Button>
            <Button
              v-if="!row.system"
              size="xs"
              icon="trash"
              @click="typeToDelete = row">Delete</Button>
          </div>
        </template>
      </GlassTable>
    </SectionCard>

    <!-- ── Users tab ─────────────────────────────────────────────── -->
    <template v-if="activeTab === 'Users'">
      <SectionCard title="User" padded>
        <div class="field-row">
          <label class="field-label">Find a user to view and manage their notifications</label>
          <Select
            :model-value="profileId"
            :on-search="searchProfiles"
            searchable
            placeholder="Search people…"
            @update:model-value="onProfileSelected($event as string)"
          />
        </div>
      </SectionCard>

      <template v-if="profileId && !loadingProfile">
        <Tabs v-model="userTab" :tabs="['Preferences', 'Delivery', 'Devices']" :accent="accent" />

        <template v-if="userTab === 'Preferences'">
          <SectionCard title="Preferences" subtitle="Types marked always-on are essential account and security messages.">
            <NotificationPreferenceMatrix
              :types="types"
              :preferences="profilePrefs"
              :accent="accent"
              :disabled="savingPreference"
              @toggle="onToggle"
            />
          </SectionCard>

          <SectionCard title="Quiet Hours" subtitle="Pause push notifications during these hours. Security alerts still come through.">
            <template #right>
              <Button size="sm" icon="pencil" @click="openEditQuietHours">Edit</Button>
            </template>
            <div class="quiet-body">
              <template v-if="profileSettings?.timeZone && profileSettings?.dndStartLocal">
                <span class="quiet-window mono">{{ profileSettings.dndStartLocal }} – {{ profileSettings.dndEndLocal }}</span>
                <span class="muted">{{ profileSettings.timeZone }}</span>
              </template>
              <span v-else class="muted">No quiet hours set — push notifications deliver at any time.</span>
            </div>
          </SectionCard>
        </template>

        <SectionCard
          v-if="userTab === 'Delivery'"
          title="Recent Delivery"
          subtitle="Latest delivery events, including why a message was suppressed or deferred."
        >
          <GlassTable :columns="eventColumns" :rows="profileEvents" empty-text="No delivery events recorded for this user.">
            <template #col-createdAt="{ row }">{{ formatDate(row.createdAt) }}</template>
            <template #col-channel="{ row }">{{ row.channel === 'PUSH' ? 'Push' : 'Email' }}</template>
            <template #col-status="{ row }">
              <Badge :color="statusColors[row.status] ?? 'var(--fg-3)'">{{ row.status }}</Badge>
            </template>
            <template #col-reason="{ row }">
              <span v-if="row.providerEvent" class="reason">{{ reasonLabels[row.providerEvent] ?? row.providerEvent }}</span>
              <span v-else-if="row.errorMessage" class="reason error">{{ row.errorMessage }}</span>
              <span v-else class="muted">—</span>
            </template>
          </GlassTable>
        </SectionCard>

        <SectionCard
          v-if="userTab === 'Devices'"
          title="Devices"
          subtitle="Devices registered to receive push notifications. Devices can only be removed by their owner."
        >
          <GlassTable :columns="deviceColumns" :rows="profileDevices" empty-text="No devices registered for this user's account.">
            <template #col-platform="{ row }">
              <div class="type-cell">
                <span class="type-name">{{ platformLabels[row.platform] ?? row.platform }}</span>
                <span v-if="row.installationId" class="type-desc mono">{{ row.installationId }}</span>
              </div>
            </template>
            <template #col-registered="{ row }">{{ formatDate(row.created) }}</template>
            <template #col-lastCheckIn="{ row }">
              <div class="check-in-cell">
                <span>{{ formatDate(row.lastCheckIn) }}</span>
                <Badge v-if="isStale(row)" color="#fbbf24">Inactive</Badge>
              </div>
            </template>
            <template #col-pushTokens="{ row }">
              <Badge :color="row.pushTokens.length ? '#34d99a' : 'var(--fg-3)'">
                {{ row.pushTokens.length ? `${row.pushTokens.length} token${row.pushTokens.length === 1 ? '' : 's'}` : 'None' }}
              </Badge>
            </template>
            <template v-if="isOwnProfile" #col-actions="{ row }">
              <Button size="xs" icon="trash" @click="deviceToRevoke = row">Remove</Button>
            </template>
          </GlassTable>
        </SectionCard>
      </template>

      <SectionCard v-else-if="profileId && loadingProfile" title="Loading" padded>
        <p class="muted">Loading notification state…</p>
      </SectionCard>
    </template>

    <!-- Create / edit type -->
    <Modal
      v-if="showTypeModal"
      :title="editingKey ? 'Edit Notification Type' : 'New Notification Type'"
      icon="settings"
      :accent="accent"
      @close="showTypeModal = false"
    >
      <div class="form-stack">
        <TextInput
          v-model="typeForm.key"
          label="Key"
          placeholder="order-updates"
          :disabled="editingKey !== null"
        />
        <p v-if="!editingKey" class="field-hint">
          Lowercase slug, immutable once created — sending code references this key.
        </p>
        <TextInput v-model="typeForm.name" label="Name" placeholder="Order updates" />
        <TextInput v-model="typeForm.description" label="Description" placeholder="Shipping and delivery status." />
        <TextInput v-model="typeForm.displayOrder" label="Display order" type="number" />
        <TextInput
          v-model="typeForm.hubSpotEmailSubscriptionId"
          label="HubSpot email subscription ID"
          placeholder="42"
        />
        <p class="field-hint">
          When set, email preference reads and writes go directly to this HubSpot communication subscription. Push remains in Bosca.
        </p>
        <Switch
          v-if="!editingType?.system"
          v-model="typeForm.optional"
          label="Users can opt out"
          :accent="accent"
        />
        <div v-else class="locked-row">
          <span class="locked-label">Users can opt out</span>
          <Badge :color="editingType.optional ? '#34d99a' : '#fbbf24'">
            {{ editingType.optional ? 'Optional' : 'Always on' }}
          </Badge>
        </div>
        <p v-if="editingType?.system" class="field-hint">
          System type: this setting is locked and the type cannot be deleted.
        </p>
        <Switch
          v-if="typeForm.optional"
          v-model="typeForm.defaultEmailEnabled"
          label="Email enabled for users by default"
          :accent="accent"
        />
        <Switch
          v-if="typeForm.optional"
          v-model="typeForm.defaultPushEnabled"
          label="Push enabled for users by default"
          :accent="accent"
        />
        <p v-if="typeForm.optional" class="field-hint">
          Each default applies until the user explicitly changes that delivery channel.
        </p>
        <Switch
          v-model="typeForm.hidden"
          label="Hide from user preferences"
          :accent="accent"
        />
        <p class="field-hint">
          Hidden types remain available to sending code but do not appear in user preference controls.
        </p>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="showTypeModal = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="savingType"
          @click="saveType">
          {{ savingType ? 'Saving...' : 'Save' }}
        </Button>
      </template>
    </Modal>

    <!-- Delete type confirm -->
    <Modal
      v-if="typeToDelete"
      title="Delete Notification Type"
      icon="trash"
      :accent="accent"
      @close="typeToDelete = null"
    >
      <p class="confirm-text">
        Delete <strong>{{ typeToDelete.name }}</strong> ({{ typeToDelete.key }})?
        Stored user preferences for this type become inactive, and any sending
        code still referencing the key will deliver unconditionally.
      </p>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="typeToDelete = null">Cancel</Button>
        <Button
          size="sm"
          primary
          accent="#f87171"
          :disabled="deletingType"
          @click="deleteType">
          {{ deletingType ? 'Deleting...' : 'Delete' }}
        </Button>
      </template>
    </Modal>

    <!-- Quiet hours -->
    <Modal
      v-if="showEditQuietHours"
      title="Quiet Hours"
      icon="moon"
      :accent="accent"
      @close="showEditQuietHours = false"
    >
      <div class="form-stack">
        <Select
          v-model="quietForm.timeZone"
          :options="timeZoneOptions"
          label="Time zone"
          searchable
          placeholder="Select a time zone…"
        />
        <TextInput
          v-model="quietForm.dndStart"
          label="Start"
          type="time"
          placeholder="22:00"
        />
        <TextInput
          v-model="quietForm.dndEnd"
          label="End"
          type="time"
          placeholder="07:00"
        />
      </div>
      <template #footer>
        <Button
          v-if="profileSettings?.timeZone"
          size="sm"
          :disabled="savingQuietHours"
          @click="saveQuietHours(true)"
        >
          Clear
        </Button>
        <span class="spacer" />
        <Button size="sm" @click="showEditQuietHours = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="savingQuietHours"
          @click="saveQuietHours(false)"
        >
          {{ savingQuietHours ? 'Saving...' : 'Save' }}
        </Button>
      </template>
    </Modal>

    <!-- Revoke device confirm -->
    <Modal
      v-if="deviceToRevoke"
      title="Remove Device"
      icon="trash"
      :accent="accent"
      @close="deviceToRevoke = null"
    >
      <p class="confirm-text">
        Remove this {{ platformLabels[deviceToRevoke.platform] ?? deviceToRevoke.platform }} device?
        It will stop receiving push notifications until you sign in on it again.
      </p>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="deviceToRevoke = null">Cancel</Button>
        <Button
          size="sm"
          primary
          accent="#f87171"
          :disabled="revoking"
          @click="revokeDevice">
          {{ revoking ? 'Removing...' : 'Remove' }}
        </Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.type-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

.type-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.type-desc {
  font-size: 11.5px;
  color: var(--fg-3);
}

.type-key {
  font-size: 11.5px;
  color: var(--fg-2);
}

.mapping-label {
  font-size: 11.5px;
  color: var(--fg-2);
}

.flag-list,
.action-list {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.field-row {
  max-width: 400px;
}

.field-label {
  display: block;
  font-size: 12px;
  color: var(--fg-3);
  margin-bottom: 6px;
}

.field-hint {
  font-size: 11.5px;
  color: var(--fg-3);
  margin-top: -8px;
}

.locked-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.locked-label {
  font-size: 13px;
  color: var(--fg-1);
}

.quiet-body {
  padding: 10px 16px 14px;
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.quiet-window {
  font-size: 13px;
  color: var(--fg-0);
}

.muted {
  font-size: 12.5px;
  color: var(--fg-3);
}

.reason {
  font-size: 12.5px;
  color: var(--fg-1);
}

.reason.error {
  color: #f87171;
}

.check-in-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.confirm-text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--fg-1);
  padding: 4px 0;
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
