<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface MessageBrandingValue {
  title?: string
  logoUrl?: string
  logoOnly?: boolean
  primaryColor?: string
  accentColor?: string
}

interface ConfigurationPermission {
  action: string
  group: { id: string; name: string }
}

interface MessageBrandingConfiguration {
  id: string
  key: string
  description: string | null
  public: boolean
  value: MessageBrandingValue | null
  permissions: ConfigurationPermission[]
}

const CONFIGURATION_KEY = 'bosca.messages.branding'
const DEFAULT_TITLE = 'Bosca'
const DEFAULT_PRIMARY_COLOR = '#0e1019'
const DEFAULT_ACCENT_COLOR = '#047a52'
const HEX_COLOR = /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/

const getConfigurationGql = gql`
  query GetBoscaMessageBrandingConfiguration {
    configurations {
      configuration(key: "bosca.messages.branding") {
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

const setConfigurationGql = gql`
  mutation SetBoscaMessageBrandingConfiguration($configuration: ConfigurationInput!) {
    configurations { setConfiguration(configuration: $configuration) { id key value } }
  }
`

const { data, refresh } = useAsyncQuery<{
  configurations: { configuration: MessageBrandingConfiguration | null }
}>('bosca-message-branding-config', getConfigurationGql, {}, { server: false })

const title = ref(DEFAULT_TITLE)
const logoUrl = ref('')
const logoOnly = ref(false)
const primaryColor = ref(DEFAULT_PRIMARY_COLOR)
const accentColor = ref(DEFAULT_ACCENT_COLOR)
const existingPermissions = ref<ConfigurationPermission[]>([])
const saving = ref(false)

function load() {
  const configuration = data.value?.configurations?.configuration
  const value = configuration?.value
  title.value = value?.title?.trim() || DEFAULT_TITLE
  logoUrl.value = value?.logoUrl?.trim() || ''
  logoOnly.value = value?.logoOnly ?? false
  primaryColor.value = value?.primaryColor?.trim() || DEFAULT_PRIMARY_COLOR
  accentColor.value = value?.accentColor?.trim() || DEFAULT_ACCENT_COLOR
  existingPermissions.value = configuration?.permissions ?? []
}

watch(data, load, { immediate: true })

const logoUrlError = computed(() => {
  const value = logoUrl.value.trim()
  if (!value) return ''
  try {
    const url = new URL(value)
    return (url.protocol === 'http:' || url.protocol === 'https:') && !!url.host
      ? ''
      : 'Logo URL must use http or https.'
  } catch {
    return 'Logo URL must be an absolute URL.'
  }
})

const primaryColorError = computed(() =>
  HEX_COLOR.test(primaryColor.value.trim()) ? '' : 'Primary color must be a CSS hex color.')
const accentColorError = computed(() =>
  HEX_COLOR.test(accentColor.value.trim()) ? '' : 'Accent color must be a CSS hex color.')
const canSave = computed(() =>
  !saving.value
  && !!title.value.trim()
  && !logoUrlError.value
  && !primaryColorError.value
  && !accentColorError.value)

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    const configuration = data.value?.configurations?.configuration
    await mutation(setConfigurationGql, {
      configuration: {
        key: CONFIGURATION_KEY,
        description: 'Bosca transactional message branding',
        public: false,
        value: {
          title: title.value.trim(),
          logoUrl: logoUrl.value.trim(),
          logoOnly: logoOnly.value,
          primaryColor: primaryColor.value.trim(),
          accentColor: accentColor.value.trim(),
        },
        permissions: existingPermissions.value.map(permission => ({
          action: permission.action,
          groupId: permission.group.id,
          entityId: configuration?.id,
        })),
      },
    })
    toast.success('Message branding saved')
    await refresh()
  } catch (error: unknown) {
    toast.error(`Failed to save message branding: ${error instanceof Error ? error.message : String(error)}`)
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
        :breadcrumb="buildBreadcrumb('Communications', 'Messages', 'Branding')"
        title="Message Branding"
        subtitle="Shared identity applied to first-party Bosca transactional messages"
      >
        <template #actions>
          <Button
            size="sm"
            primary
            icon="check"
            :accent="accent"
            :disabled="!canSave"
            @click="save"
          >
            {{ saving ? 'Saving…' : 'Save' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <div class="branding-stack">
      <SectionCard
        title="Identity"
        subtitle="The shared title and externally hosted logo used by message templates"
        padded
      >
        <div class="form-stack">
          <TextInput
            v-model="title"
            label="Title"
            placeholder="Bosca"
          />
          <p class="field-help">Used in email mastheads, document titles, and brand-aware message copy.</p>

          <TextInput
            v-model="logoUrl"
            type="url"
            label="Logo URL"
            placeholder="https://cdn.example.com/email-logo.png"
          />
          <p :class="logoUrlError ? 'field-error' : 'field-help'">
            {{ logoUrlError || 'Use an absolute, publicly accessible image URL. Leave blank for the bundled Bosca mark.' }}
          </p>

          <Switch
            v-model="logoOnly"
            :accent="accent"
            label="Show only the logo in the masthead"
          />
          <p class="field-help">
            When disabled, the configured title appears beside the logo.
          </p>
        </div>
      </SectionCard>

      <SectionCard
        title="Colors"
        subtitle="Primary actions and text accents used across transactional message templates"
        padded
      >
        <div class="color-grid">
          <div class="color-field">
            <ColorPicker
              v-model="primaryColor"
              label="Primary Color"
              :presets="['#0e1019', '#172554', '#312e81', '#3f1d2e', '#1f2937']"
            />
            <p :class="primaryColorError ? 'field-error' : 'field-help'">
              {{ primaryColorError || 'Used for buttons, headings, and the masthead title.' }}
            </p>
          </div>
          <div class="color-field">
            <ColorPicker
              v-model="accentColor"
              label="Accent Color"
              :presets="['#047a52', '#0369a1', '#6d28d9', '#be123c', '#b45309']"
            />
            <p :class="accentColorError ? 'field-error' : 'field-help'">
              {{ accentColorError || 'Used for labels, links, and highlighted panel borders.' }}
            </p>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Runtime" padded>
        <p class="runtime-note">
          Changes apply to new sends and Studio previews without restarting the server or runner.
          Existing delivered messages are unchanged.
        </p>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.branding-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 9px;
  max-width: 680px;
}

.color-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 28px;
  max-width: 760px;
}

.color-field {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.field-help,
.field-error,
.runtime-note {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
}

.field-help,
.runtime-note {
  color: var(--fg-3);
}

.field-error {
  color: var(--danger, #f26d6d);
}

@media (max-width: 720px) {
  .color-grid {
    grid-template-columns: 1fr;
  }
}
</style>
