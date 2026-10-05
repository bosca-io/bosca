<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface SendGridValue {
  apiKey?: string
  webhookVerificationKey?: string
}

interface SendGridPermission {
  action: string
  group: { id: string; name: string }
}

interface SendGridConfig {
  id: string
  key: string
  description: string
  public: boolean
  value: SendGridValue | null
  permissions: SendGridPermission[]
}

const SECRET_MASK = '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022'

const getConfigGql = gql`
  query GetSendGridConfiguration {
    configurations {
      configuration(key: "sendgrid") {
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
  mutation SetSendGridConfiguration($configuration: ConfigurationInput!) {
    configurations { setConfiguration(configuration: $configuration) { id key value } }
  }
`

const { data: configData, refresh } = useAsyncQuery<{
  configurations: { configuration: SendGridConfig | null }
}>('sendgrid-config', getConfigGql, {}, { server: false })

const apiKey = ref('')
const apiKeyChanged = ref(false)
const hasStoredApiKey = ref(false)
const webhookVerificationKey = ref('')
const webhookVerificationKeyChanged = ref(false)
const hasStoredWebhookVerificationKey = ref(false)
const existingPermissions = ref<SendGridPermission[]>([])
const saving = ref(false)

function load() {
  const config = configData.value?.configurations?.configuration
  const value = config?.value ?? {}
  hasStoredApiKey.value = !!value.apiKey
  apiKey.value = value.apiKey ? SECRET_MASK : ''
  apiKeyChanged.value = false
  hasStoredWebhookVerificationKey.value = !!value.webhookVerificationKey
  webhookVerificationKey.value = value.webhookVerificationKey ? SECRET_MASK : ''
  webhookVerificationKeyChanged.value = false
  existingPermissions.value = config?.permissions ?? []
}

watch(configData, load)
onMounted(load)

function updateApiKey(value: string) {
  apiKey.value = value
  apiKeyChanged.value = true
}

function updateWebhookVerificationKey(value: string) {
  webhookVerificationKey.value = value
  webhookVerificationKeyChanged.value = true
}

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const config = configData.value?.configurations?.configuration
    const existing = config?.value
    const value: SendGridValue = {}

    if (apiKeyChanged.value) value.apiKey = apiKey.value
    else if (hasStoredApiKey.value && existing?.apiKey) value.apiKey = existing.apiKey

    if (webhookVerificationKeyChanged.value) {
      value.webhookVerificationKey = webhookVerificationKey.value
    } else if (hasStoredWebhookVerificationKey.value && existing?.webhookVerificationKey) {
      value.webhookVerificationKey = existing.webhookVerificationKey
    }

    await mutation(setConfigGql, {
      configuration: {
        key: 'sendgrid',
        description: 'SendGrid Email Integration Configuration',
        public: false,
        value,
        permissions: existingPermissions.value.map(permission => ({
          action: permission.action,
          groupId: permission.group.id,
          entityId: config?.id,
        })),
      },
    })
    toast.success('SendGrid configuration saved')
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
        :breadcrumb="buildBreadcrumb('System', 'Integrations', 'SendGrid')"
        title="SendGrid"
        subtitle="Email delivery and delivery-event tracking"
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

    <div class="sendgrid-stack">
      <SectionCard
        title="API Credentials"
        subtitle="Credentials used by the server and runner for email delivery"
        padded
      >
        <div class="form-stack">
          <TextInput
            :model-value="apiKey"
            type="password"
            label="API Key"
            icon="key"
            placeholder="Paste a SendGrid API key"
            @update:model-value="updateApiKey"
          />
          <p class="field-help">
            Use a restricted key with Mail Send access. Changes apply to new sends without restarting services.
          </p>
        </div>
      </SectionCard>

      <SectionCard
        title="Signed Event Webhook"
        subtitle="Verify delivery, bounce, open, click, and unsubscribe events from SendGrid"
        padded
      >
        <div class="form-stack">
          <TextInput
            :model-value="webhookVerificationKey"
            type="password"
            label="Verification Key"
            icon="lock"
            placeholder="Paste the SendGrid webhook verification key"
            @update:model-value="updateWebhookVerificationKey"
          />
          <div class="endpoint">
            <span class="endpoint-label">Webhook path</span>
            <code>/api/v1/webhooks/sendgrid</code>
          </div>
          <p class="field-help">
            Enable signed Event Webhooks in SendGrid and send them to this path on the public Bosca API origin.
          </p>
        </div>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.sendgrid-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.field-help {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--fg-3);
}

.endpoint {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm);
  background: var(--bg-2);
}

.endpoint-label {
  color: var(--fg-3);
  font-size: 11.5px;
}

.endpoint code {
  color: var(--fg-1);
  font-size: 12px;
}
</style>
