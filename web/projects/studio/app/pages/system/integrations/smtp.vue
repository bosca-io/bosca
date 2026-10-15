<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface SmtpValue {
  host?: string
  port?: number
  security?: string
  username?: string
  password?: string
}
interface SmtpConfiguration {
  id: string
  value: SmtpValue | null
  permissions: Array<{ action: string; group: { id: string } }>
}
const { data, status, error, refresh } = useAsyncQuery<{
  configurations: { configuration: SmtpConfiguration | null }
}>('smtp-config', gql`
  query GetSmtpConfiguration {
    configurations {
      configuration(key: "smtp") { id value permissions { action group { id } } }
    }
  }
`, {}, { server: false })

const host = ref('')
const port = ref('587')
const security = ref('STARTTLS')
const username = ref('')
const password = ref('')
const passwordChanged = ref(false)
const saving = ref(false)
const securityOptions = [
  { value: 'STARTTLS', label: 'STARTTLS' },
  { value: 'TLS', label: 'TLS' },
  { value: 'NONE', label: 'None (plain SMTP)' },
]
watch(data, () => {
  const value = data.value?.configurations.configuration?.value
  host.value = value?.host ?? ''
  port.value = String(value?.port ?? 587)
  security.value = value?.security ?? 'STARTTLS'
  username.value = value?.username ?? ''
  password.value = value?.password ? '••••••••' : ''
  passwordChanged.value = false
}, { immediate: true })

function updatePassword(value: string) {
  password.value = value
  passwordChanged.value = true
}

const validationError = computed(() => {
  if (!host.value.trim() || /\s/.test(host.value.trim())) return 'Enter an SMTP hostname.'
  if (!/^\d+$/.test(port.value) || Number(port.value) < 1 || Number(port.value) > 65535) return 'Port must be between 1 and 65535.'
  if (!securityOptions.some(option => option.value === security.value)) return 'Choose a connection security option.'
  if (!!username.value.trim() !== !!password.value) return 'Set both username and password, or leave both empty for an unauthenticated relay.'
  return ''
})
const canSave = computed(() => status.value === 'success' && !saving.value && !validationError.value)

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    const config = data.value?.configurations.configuration
    await mutation(gql`
      mutation SetSmtpConfiguration($configuration: ConfigurationInput!) {
        configurations { setConfiguration(configuration: $configuration) { id } }
      }
    `, {
      configuration: {
        key: 'smtp',
        description: 'SMTP Email Integration Configuration',
        public: false,
        value: {
          host: host.value.trim(),
          port: Number(port.value),
          security: security.value,
          username: username.value.trim(),
          password: passwordChanged.value ? password.value : (config?.value?.password ?? ''),
        },
        permissions: (config?.permissions ?? []).map(permission => ({
          action: permission.action, groupId: permission.group.id, entityId: config?.id,
        })),
      },
    })
    toast.success('SMTP configuration saved')
    await refresh()
  } catch (error: unknown) {
    toast.error(`Failed to save SMTP configuration: ${error instanceof Error ? error.message : String(error)}`)
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
        :breadcrumb="buildBreadcrumb('System', 'Integrations', 'SMTP')"
        title="SMTP"
        subtitle="Email delivery through an SMTP server">
        <template #actions>
          <Button
            size="sm"
            primary
            :accent="accent"
            icon="check"
            :disabled="!canSave"
            @click="save">
            {{ saving ? 'Saving…' : 'Save' }}
          </Button>
        </template>
      </PageHeader>
    </template>
    <div class="smtp-settings">
      <p v-if="error" class="field-error">SMTP settings could not be loaded. Reload the page to try again.</p>
      <SectionCard title="SMTP Server" padded>
        <div class="form-stack">
          <TextInput v-model="host" label="Host" placeholder="smtp.example.com" />
          <TextInput v-model="port" label="Port" type="number" />
          <Select v-model="security" label="Connection Security" :options="securityOptions" />
          <p>Common ports are 587 for STARTTLS, 465 for TLS, and 25 for plain SMTP. Use the settings supplied by your mail server.</p>
        </div>
      </SectionCard>
      <SectionCard title="Authentication" padded>
        <div class="form-stack">
          <TextInput v-model="username" label="Username" autocomplete="off" />
          <TextInput
            :model-value="password"
            label="Password"
            type="password"
            autocomplete="new-password"
            @update:model-value="updatePassword" />
          <p>Leave both fields empty for a relay that does not require authentication.</p>
          <p v-if="validationError" class="field-error">{{ validationError }}</p>
          <p>Changes apply to new sends. Select SMTP in <NuxtLink to="/system/integrations/email">Email settings</NuxtLink> to use this server.</p>
        </div>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.smtp-settings { display: flex; flex-direction: column; gap: 14px; }
.form-stack { display: flex; flex-direction: column; gap: 10px; max-width: 680px; }
.smtp-settings p { margin: 0; color: var(--fg-3); font-size: 12px; line-height: 1.5; }
.smtp-settings .field-error { color: var(--danger, #ef6262); }
.smtp-settings a { color: var(--fg-2); }
</style>
