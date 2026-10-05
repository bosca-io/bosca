<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface FcmValue {
  serviceAccountJson?: string
}

interface ApnsValue {
  teamId?: string
  keyId?: string
  bundleId?: string
  privateKey?: string
  sandbox?: boolean
}

interface PushValue {
  enabled?: boolean
  fcm?: FcmValue
  apns?: ApnsValue
}

interface PushPermission {
  action: string
  group: { id: string; name: string }
}

interface PushConfig {
  id: string
  key: string
  description: string | null
  public: boolean
  value: PushValue | null
  permissions: PushPermission[]
}

const SECRET_MASK = '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022'

const getConfigGql = gql`
  query GetPushConfiguration {
    configurations {
      configuration(key: "push") {
        id
        key
        description
        public
        value
        permissions {
          action
          group { id name }
        }
      }
    }
  }
`

const setConfigGql = gql`
  mutation SetPushConfiguration($configuration: ConfigurationInput!) {
    configurations { setConfiguration(configuration: $configuration) { id key value } }
  }
`

const { data: configData, refresh } = useAsyncQuery<{
  configurations: { configuration: PushConfig | null }
}>('push-config', getConfigGql, {}, { server: false })

const enabled = ref(false)
const fcmEnabled = ref(false)
const serviceAccountJson = ref('')
const serviceAccountJsonChanged = ref(false)
const hasStoredServiceAccountJson = ref(false)
const apnsEnabled = ref(false)
const teamId = ref('')
const keyId = ref('')
const bundleId = ref('')
const privateKey = ref('')
const privateKeyChanged = ref(false)
const hasStoredPrivateKey = ref(false)
const sandbox = ref(true)
const existingPermissions = ref<PushPermission[]>([])
const saving = ref(false)

const serviceAccountJsonModel = computed({
  get: () => serviceAccountJson.value,
  set: (value: string) => {
    serviceAccountJson.value = value
    serviceAccountJsonChanged.value = true
  },
})

const privateKeyModel = computed({
  get: () => privateKey.value,
  set: (value: string) => {
    privateKey.value = value
    privateKeyChanged.value = true
  },
})

const apnsComplete = computed(() =>
  !!teamId.value.trim()
  && !!keyId.value.trim()
  && !!bundleId.value.trim()
  && (privateKeyChanged.value ? !!privateKey.value.trim() : hasStoredPrivateKey.value),
)

function load() {
  const config = configData.value?.configurations?.configuration
  const value = config?.value ?? {}
  enabled.value = value.enabled ?? false
  fcmEnabled.value = value.fcm != null
  hasStoredServiceAccountJson.value = !!value.fcm?.serviceAccountJson
  serviceAccountJson.value = value.fcm?.serviceAccountJson ? SECRET_MASK : ''
  serviceAccountJsonChanged.value = false
  apnsEnabled.value = value.apns != null
  teamId.value = value.apns?.teamId ?? ''
  keyId.value = value.apns?.keyId ?? ''
  bundleId.value = value.apns?.bundleId ?? ''
  hasStoredPrivateKey.value = !!value.apns?.privateKey
  privateKey.value = value.apns?.privateKey ? SECRET_MASK : ''
  privateKeyChanged.value = false
  sandbox.value = value.apns?.sandbox ?? true
  existingPermissions.value = config?.permissions ?? []
}

watch(configData, load)
onMounted(load)

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const config = configData.value?.configurations?.configuration
    const existing = config?.value
    const value: PushValue = { enabled: enabled.value }

    if (fcmEnabled.value) {
      const fcm: FcmValue = {}
      if (serviceAccountJsonChanged.value) {
        if (serviceAccountJson.value.trim()) fcm.serviceAccountJson = serviceAccountJson.value
      } else if (hasStoredServiceAccountJson.value && existing?.fcm?.serviceAccountJson) {
        fcm.serviceAccountJson = existing.fcm.serviceAccountJson
      }
      value.fcm = fcm
    }

    if (apnsEnabled.value) {
      const apns: ApnsValue = {
        teamId: teamId.value.trim() || undefined,
        keyId: keyId.value.trim() || undefined,
        bundleId: bundleId.value.trim() || undefined,
        sandbox: sandbox.value,
      }
      if (privateKeyChanged.value) {
        if (privateKey.value.trim()) apns.privateKey = privateKey.value
      } else if (hasStoredPrivateKey.value && existing?.apns?.privateKey) {
        apns.privateKey = existing.apns.privateKey
      }
      value.apns = apns
    }

    await mutation(setConfigGql, {
      configuration: {
        key: 'push',
        description: 'Push Notification Delivery Configuration',
        public: false,
        value,
        permissions: existingPermissions.value.map(permission => ({
          action: permission.action,
          groupId: permission.group.id,
          entityId: config?.id,
        })),
      },
    })
    toast.success('Push notification configuration saved')
    await refresh()
  } catch (e) {
    toast.error(`Failed to save: ${e instanceof Error ? e.message : String(e)}`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Integrations', 'Push Notifications')"
        title="Push Notifications"
        subtitle="Mobile and web delivery through Firebase and Apple Push Notification service"
      >
        <template #actions>
          <Button
            size="sm"
            primary
            :accent="accent"
            icon="check"
            :disabled="saving"
            @click="save"
          >
            {{ saving ? 'Saving…' : 'Save' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div class="push-stack">
      <SectionCard
        title="Delivery"
        subtitle="Control whether configured push providers may deliver notifications"
        padded
      >
        <div class="field">
          <Switch v-model="enabled" :accent="accent" label="Enable push delivery" />
          <p class="field-help">Changes apply to new sends without restarting the server or runner.</p>
        </div>
      </SectionCard>

      <SectionCard
        title="Firebase Cloud Messaging"
        subtitle="Android, web, desktop, and FCM-issued iOS tokens"
        padded
      >
        <div class="form-stack">
          <div class="field">
            <Switch v-model="fcmEnabled" :accent="accent" label="Configure Firebase Cloud Messaging" />
            <p class="field-help">
              When enabled without service-account JSON, Firebase uses Application Default Credentials.
            </p>
          </div>
          <div v-if="fcmEnabled" class="field">
            <Textarea
              v-model="serviceAccountJsonModel"
              label="Service Account JSON"
              placeholder="Paste the complete Google service-account JSON document"
              :rows="8"
              mono
            />
            <p class="field-help">Leave blank to use Application Default Credentials.</p>
          </div>
        </div>
      </SectionCard>

      <SectionCard
        title="Apple Push Notification Service"
        subtitle="Direct delivery to APNs-issued iOS tokens"
        padded
      >
        <div class="form-stack">
          <div class="field">
            <Switch v-model="apnsEnabled" :accent="accent" label="Configure Apple Push Notification service" />
            <p class="field-help">Direct APNs delivery requires all four credential fields.</p>
          </div>
          <template v-if="apnsEnabled">
            <div class="form-grid">
              <TextInput
                v-model="teamId"
                label="Team ID"
                placeholder="Apple Developer Team ID"
                mono
              />
              <TextInput
                v-model="keyId"
                label="Key ID"
                placeholder="APNs authentication key ID"
                mono
              />
            </div>
            <TextInput
              v-model="bundleId"
              label="Bundle ID"
              placeholder="com.example.app"
              mono
            />
            <div class="field">
              <Textarea
                v-model="privateKeyModel"
                label="Private Key"
                placeholder="Paste the complete PKCS#8 APNs private key"
                :rows="8"
                mono
              />
              <p class="field-help">Use the PEM-encoded contents of the APNs .p8 key.</p>
            </div>
            <div class="field">
              <Switch v-model="sandbox" :accent="accent" label="Use APNs sandbox" />
              <p class="field-help">
                {{ sandbox ? 'Development builds will use the APNs sandbox.' : 'Production builds will use the APNs production service.' }}
              </p>
            </div>
            <p v-if="!apnsComplete" class="configuration-warning">
              APNs delivery remains unavailable until Team ID, Key ID, Bundle ID, and Private Key are all set.
            </p>
          </template>
        </div>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.push-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-stack,
.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-stack {
  gap: 14px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.field-help,
.configuration-warning {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--fg-3);
}

.configuration-warning {
  color: var(--warning, #e8a84c);
}

@media (max-width: 640px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
