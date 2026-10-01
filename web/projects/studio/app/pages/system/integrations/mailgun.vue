<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface MailgunValue {
  apiKey?: string
  domain?: string
  apiBaseUrl?: string
}

interface MailgunPermission {
  action: string
  group: { id: string; name: string }
}

interface MailgunConfig {
  id: string
  key: string
  description: string
  public: boolean
  value: MailgunValue | null
  permissions: MailgunPermission[]
}

const CONFIGURATION_KEY = 'mailgun'
const DEFAULT_API_BASE_URL = 'https://api.mailgun.net'
const SECRET_MASK = '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022'

const getConfigGql = gql`
  query GetMailgunConfiguration {
    configurations {
      configuration(key: "mailgun") {
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
  mutation SetMailgunConfiguration($configuration: ConfigurationInput!) {
    configurations { setConfiguration(configuration: $configuration) { id key value } }
  }
`

const { data: configData, refresh } = useAsyncQuery<{
  configurations: { configuration: MailgunConfig | null }
}>('mailgun-config', getConfigGql, {}, { server: false })

const apiKey = ref('')
const apiKeyChanged = ref(false)
const hasStoredApiKey = ref(false)
const domain = ref('')
const apiBaseUrl = ref(DEFAULT_API_BASE_URL)
const existingPermissions = ref<MailgunPermission[]>([])
const saving = ref(false)

function load() {
  const config = configData.value?.configurations?.configuration
  const value = config?.value ?? {}
  hasStoredApiKey.value = !!value.apiKey
  apiKey.value = value.apiKey ? SECRET_MASK : ''
  apiKeyChanged.value = false
  domain.value = value.domain ?? ''
  apiBaseUrl.value = value.apiBaseUrl?.trim() || DEFAULT_API_BASE_URL
  existingPermissions.value = config?.permissions ?? []
}

watch(configData, load)
onMounted(load)

function updateApiKey(value: string) {
  apiKey.value = value
  apiKeyChanged.value = true
}

const apiBaseUrlError = computed(() => {
  const value = apiBaseUrl.value.trim()
  try {
    const url = new URL(value)
    return (url.protocol === 'http:' || url.protocol === 'https:') && !!url.host
      ? ''
      : 'API base URL must use http or https.'
  } catch {
    return 'API base URL must be an absolute URL.'
  }
})

const canSave = computed(() =>
  !saving.value
  && !!apiKey.value.trim()
  && !!domain.value.trim()
  && !apiBaseUrlError.value)

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    const config = configData.value?.configurations?.configuration
    const existing = config?.value
    const value: MailgunValue = {
      domain: domain.value.trim(),
      apiBaseUrl: apiBaseUrl.value.trim(),
    }

    if (apiKeyChanged.value) value.apiKey = apiKey.value.trim()
    else if (hasStoredApiKey.value && existing?.apiKey) value.apiKey = existing.apiKey

    await mutation(setConfigGql, {
      configuration: {
        key: CONFIGURATION_KEY,
        description: 'Mailgun Email Integration Configuration',
        public: false,
        value,
        permissions: existingPermissions.value.map(permission => ({
          action: permission.action,
          groupId: permission.group.id,
          entityId: config?.id,
        })),
      },
    })
    toast.success('Mailgun configuration saved')
    await refresh()
  } catch (error: unknown) {
    toast.error(`Failed to save Mailgun configuration: ${error instanceof Error ? error.message : String(error)}`)
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
        :breadcrumb="buildBreadcrumb('System', 'Integrations', 'Mailgun')"
        title="Mailgun"
        subtitle="Email delivery through a verified Mailgun sending domain"
      >
        <template #actions>
          <Button
            size="sm"
            primary
            :accent="accent"
            icon="check"
            :disabled="!canSave"
            @click="save"
          >
            {{ saving ? 'Saving…' : 'Save' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div class="mailgun-stack">
      <SectionCard
        title="API Credentials"
        subtitle="Credentials used by the server and runner for Mailgun delivery"
        padded
      >
        <div class="form-stack">
          <TextInput
            :model-value="apiKey"
            type="password"
            label="API Key"
            icon="key"
            placeholder="Paste a Mailgun API key"
            @update:model-value="updateApiKey"
          />
          <p class="field-help">
            Changes apply to new sends without restarting services.
          </p>
        </div>
      </SectionCard>

      <SectionCard
        title="Sending Domain"
        subtitle="Mailgun domain and regional API origin used for outgoing messages"
        padded
      >
        <div class="form-stack">
          <TextInput
            v-model="domain"
            label="Domain"
            placeholder="mg.example.com"
          />
          <p class="field-help">
            Enter a verified sending domain from your Mailgun account.
          </p>
          <TextInput
            v-model="apiBaseUrl"
            type="url"
            label="API Base URL"
            placeholder="https://api.mailgun.net"
          />
          <p :class="apiBaseUrlError ? 'field-error' : 'field-help'">
            {{ apiBaseUrlError || 'Use https://api.eu.mailgun.net for domains hosted in Mailgun’s EU region.' }}
          </p>
        </div>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.mailgun-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 680px;
}

.field-help,
.field-error {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
}

.field-help {
  color: var(--fg-3);
}

.field-error {
  color: var(--danger, #ef6262);
}
</style>
