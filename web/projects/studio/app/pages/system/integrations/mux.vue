<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const toast = useToast()

interface MuxValue {
  tokenId?: string
  tokenSecret?: string
  playbackPolicy?: string
  maxWaitSeconds?: number
  videoQuality?: string | null
  maxResolutionTier?: string | null
  mp4Support?: boolean
  defaultSubtitleLanguage?: string | null
  test?: boolean
}

interface MuxPermission {
  action: string
  group: { id: string; name: string }
}

interface MuxConfig {
  id: string
  key: string
  description: string | null
  public: boolean
  value: MuxValue | null
  permissions: MuxPermission[]
}

const SECRET_MASK = '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022'

const getConfigGql = gql`
  query GetMuxConfiguration {
    configurations {
      configuration(key: "mux") {
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
  mutation SetMuxConfiguration($configuration: ConfigurationInput!) {
    configurations { setConfiguration(configuration: $configuration) { id key value } }
  }
`

const { data: configData, refresh } = useAsyncQuery<{
  configurations: { configuration: MuxConfig | null }
}>('mux-config', getConfigGql, {}, { server: false })

const tokenId = ref('')
const tokenIdChanged = ref(false)
const hasStoredTokenId = ref(false)
const tokenSecret = ref('')
const tokenSecretChanged = ref(false)
const hasStoredTokenSecret = ref(false)
const playbackPolicy = ref('public')
const maxWaitSeconds = ref<number | null>(3600)
const videoQuality = ref<string | null>(null)
const maxResolutionTier = ref<string | null>(null)
const mp4Support = ref(true)
const defaultSubtitleLanguage = ref('en')
const testMode = ref(false)
// This page has no permission editor, so existing config-level permissions are
// round-tripped verbatim on save rather than cleared.
const existingPermissions = ref<MuxPermission[]>([])
const saving = ref(false)

const playbackPolicyOptions: SelectOption[] = [
  { value: 'public', label: 'Public' },
  { value: 'signed', label: 'Signed' },
]

const videoQualityOptions: SelectOption[] = [
  { value: 'none', label: 'Default' },
  { value: 'basic', label: 'Basic' },
  { value: 'plus', label: 'Plus' },
  { value: 'premium', label: 'Premium' },
]

const maxResolutionOptions: SelectOption[] = [
  { value: 'none', label: 'Default (1080p)' },
  { value: '1080p', label: '1080p' },
  { value: '1440p', label: '1440p' },
  { value: '2160p', label: '2160p (4K)' },
]

const videoQualityModel = computed({
  get: () => videoQuality.value ?? 'none',
  set: (v: string | string[] | null) => {
    const value = Array.isArray(v) ? v[0] ?? null : v
    videoQuality.value = !value || value === 'none' ? null : value
  },
})

const maxResolutionModel = computed({
  get: () => maxResolutionTier.value ?? 'none',
  set: (v: string | string[] | null) => {
    const value = Array.isArray(v) ? v[0] ?? null : v
    maxResolutionTier.value = !value || value === 'none' ? null : value
  },
})

function load() {
  const config = configData.value?.configurations?.configuration
  if (!config) return
  const value = config.value ?? {}
  hasStoredTokenId.value = !!value.tokenId
  hasStoredTokenSecret.value = !!value.tokenSecret
  tokenId.value = value.tokenId ? SECRET_MASK : ''
  tokenIdChanged.value = false
  tokenSecret.value = value.tokenSecret ? SECRET_MASK : ''
  tokenSecretChanged.value = false
  playbackPolicy.value = value.playbackPolicy || 'public'
  maxWaitSeconds.value = value.maxWaitSeconds ?? 3600
  videoQuality.value = value.videoQuality ?? null
  maxResolutionTier.value = value.maxResolutionTier ?? null
  mp4Support.value = value.mp4Support ?? true
  defaultSubtitleLanguage.value = value.defaultSubtitleLanguage ?? 'en'
  testMode.value = value.test ?? false
  existingPermissions.value = config.permissions ?? []
}

watch(configData, load)
onMounted(load)

function onTokenIdInput() {
  tokenIdChanged.value = true
}

function onTokenSecretInput() {
  tokenSecretChanged.value = true
}

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const config = configData.value?.configurations?.configuration
    const existing = config?.value
    const value: MuxValue = {
      playbackPolicy: playbackPolicy.value,
      maxWaitSeconds: Number(maxWaitSeconds.value ?? 3600),
      videoQuality: videoQuality.value ?? undefined,
      maxResolutionTier: maxResolutionTier.value ?? undefined,
      mp4Support: mp4Support.value,
      defaultSubtitleLanguage: defaultSubtitleLanguage.value || undefined,
      test: testMode.value,
    }
    if (tokenIdChanged.value) value.tokenId = tokenId.value
    else if (existing?.tokenId) value.tokenId = existing.tokenId

    if (tokenSecretChanged.value) value.tokenSecret = tokenSecret.value
    else if (existing?.tokenSecret) value.tokenSecret = existing.tokenSecret

    await mutation(setConfigGql, {
      configuration: {
        key: 'mux',
        description: 'Mux Video Integration Configuration',
        public: false,
        value,
        // No permission editor on this page; preserve any existing config
        // permissions instead of clearing them.
        permissions: existingPermissions.value.map(p => ({
          action: p.action,
          groupId: p.group.id,
          entityId: config?.id,
        })),
      },
    })
    toast.success('Mux configuration saved')
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
        :breadcrumb="buildBreadcrumb('System', 'Integrations', 'Mux')"
        title="Mux"
        subtitle="Video hosting, encoding, and streaming"
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

    <div class="mux-stack">
      <SectionCard
        title="API Credentials"
        subtitle="Mux API access-token credentials"
        padded
      >
        <div class="form-stack">
          <TextInput
            v-model="tokenId"
            type="password"
            label="Token ID"
            icon="key"
            placeholder="Mux API access-token ID"
            @blur="onTokenIdInput"
          />
          <TextInput
            v-model="tokenSecret"
            type="password"
            label="Token Secret"
            icon="lock"
            placeholder="Mux API secret key"
            @blur="onTokenSecretInput"
          />
        </div>
      </SectionCard>

      <SectionCard
        title="Asset Settings"
        subtitle="Controls how Mux processes new video assets"
        padded
      >
        <div class="form-stack">
          <div class="field">
            <label class="field-label">Playback Policy</label>
            <Select
              v-model="playbackPolicy"
              :options="playbackPolicyOptions"
              :accent="accent"
            />
            <p class="field-help">Use <strong>Signed</strong> for token-gated playback.</p>
          </div>
          <NumberInput
            v-model="maxWaitSeconds"
            label="Max Wait Seconds"
            :min="60"
            :step="60"
          />
        </div>
      </SectionCard>

      <SectionCard
        title="Quality & Encoding"
        subtitle="Defaults applied to newly processed assets; individual items can override"
        padded
      >
        <div class="form-stack">
          <div class="field">
            <label class="field-label">Video Quality</label>
            <Select
              v-model="videoQualityModel"
              :options="videoQualityOptions"
              :accent="accent"
            />
            <p class="field-help">Higher tiers produce better quality at higher cost.</p>
          </div>
          <div class="field">
            <label class="field-label">Max Resolution</label>
            <Select
              v-model="maxResolutionModel"
              :options="maxResolutionOptions"
              :accent="accent"
            />
            <p class="field-help">Assets are encoded up to this resolution if the source supports it.</p>
          </div>
          <div class="field">
            <Switch v-model="mp4Support" :accent="accent" label="MP4 downloads" />
            <p class="field-help">Request static MP4 renditions in addition to HLS streaming.</p>
          </div>
          <TextInput
            v-model="defaultSubtitleLanguage"
            label="Auto-Transcription Language"
            placeholder="en"
          />
          <div class="field">
            <Switch v-model="testMode" :accent="accent" label="Test mode" />
            <p class="field-help">Assets are watermarked, limited to 10 seconds, and deleted after 24 hours. For development only.</p>
          </div>
        </div>
      </SectionCard>

    </div>
  </PageShell>
</template>

<style scoped>
.mux-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.field-help {
  margin: 0;
  font-size: 11.5px;
  color: var(--fg-3);
}
</style>
