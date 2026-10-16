<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface EmailConfiguration {
  id: string
  value: { type?: string | null } | null
  permissions: Array<{ action: string; group: { id: string } }>
}

const { data, status, error, refresh } = useAsyncQuery<{
  configurations: { configuration: EmailConfiguration | null }
}>('email-provider-config', gql`
  query GetEmailProviderConfiguration {
    configurations {
      configuration(key: "mailer") {
        id value permissions { action group { id } }
      }
    }
  }
`, {}, { server: false })

const provider = ref('default')
const saving = ref(false)
const providers = [
  { value: 'default', label: 'Use deployment setting' },
  { value: 'sendgrid', label: 'SendGrid' },
  { value: 'mailgun', label: 'Mailgun' },
  { value: 'smtp', label: 'SMTP' },
]
watch(data, () => { provider.value = data.value?.configurations.configuration?.value?.type ?? 'default' }, { immediate: true })
const canSave = computed(() => status.value === 'success' && !saving.value && providers.some(item => item.value === provider.value))

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    const config = data.value?.configurations.configuration
    await mutation(gql`
      mutation SetEmailProviderConfiguration($configuration: ConfigurationInput!) {
        configurations { setConfiguration(configuration: $configuration) { id } }
      }
    `, {
      configuration: {
        key: 'mailer',
        description: 'Email Provider Configuration',
        public: false,
        value: { type: provider.value === 'default' ? null : provider.value },
        permissions: (config?.permissions ?? []).map(permission => ({
          action: permission.action, groupId: permission.group.id, entityId: config?.id,
        })),
      },
    })
    toast.success('Email provider saved')
    await refresh()
  } catch (error: unknown) {
    toast.error(`Failed to save email provider: ${error instanceof Error ? error.message : String(error)}`)
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
        :breadcrumb="buildBreadcrumb('System', 'Integrations', 'Email')"
        title="Email"
        subtitle="Choose the provider for outgoing email"
      >
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
    <SectionCard title="Email Provider" padded>
      <div class="email-settings">
        <p v-if="error" class="field-error">Email settings could not be loaded. Reload the page to try again.</p>
        <Select v-model="provider" label="Provider" :options="providers" />
        <p>Save the provider’s connection settings before selecting it. Changes apply to new sends without restarting services.</p>
        <p>Use deployment setting follows the provider selected for your deployment.</p>
        <div class="provider-links">
          <NuxtLink to="/system/integrations/sendgrid">Configure SendGrid</NuxtLink>
          <NuxtLink to="/system/integrations/mailgun">Configure Mailgun</NuxtLink>
          <NuxtLink to="/system/integrations/smtp">Configure SMTP</NuxtLink>
        </div>
      </div>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.email-settings { display: flex; flex-direction: column; gap: 12px; max-width: 680px; }
.email-settings p { margin: 0; color: var(--fg-3); font-size: 12px; line-height: 1.5; }
.email-settings .field-error { color: var(--danger, #ef6262); }
.provider-links { display: flex; flex-wrap: wrap; gap: 16px; font-size: 12px; }
.provider-links a { color: var(--fg-2); }
</style>
