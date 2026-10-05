<script setup lang="ts">
import gql from 'graphql-tag'
import jsonata from 'jsonata'
import type { SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query, mutation } = useGraphQL()
const toast = useToast()

interface HubSpotValue {
  token?: string
  contactToCompanyAssociationTypeId?: number
  contactToCompanyAssociationCategory?: string
  listIds?: string[]
  subscriptionIds?: string[]
  expressions?: { generic?: string, organization?: string }
}

interface HubSpotConfig {
  id: string
  key: string
  description: string | null
  public: boolean
  value: HubSpotValue | null
}

const SECRET_MASK = '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022'

const getConfigGql = gql`
  query GetHubSpotConfiguration {
    configurations {
      configuration(key: "hubspot") {
        id
        key
        description
        public
        value
      }
    }
  }
`

const setConfigGql = gql`
  mutation SetHubSpotConfiguration($configuration: ConfigurationInput!) {
    configurations { setConfiguration(configuration: $configuration) { id key value } }
  }
`

const syncAllGql = gql`
  mutation SyncAllHubSpot {
    profiles { profile { thirdparty { syncAll } } }
  }
`

const syncProfileGql = gql`
  mutation SyncHubSpotProfile($profileId: UUID!) {
    profiles { profile { thirdparty { sync(profileId: $profileId) } } }
  }
`

const searchProfilesGql = gql`
  query SearchHubSpotPreviewProfiles($query: String!, $limit: Int!, $offset: Int!) {
    search {
      search(query: {
        query: $query
        filter: ["_type = \\"profile\\""]
        storageSystemName: "Admin Search Index"
        limit: $limit
        offset: $offset
      }) {
        documents { profile { id name type } }
      }
    }
  }
`

const getProfileContextGql = gql`
  query GetProfileThirdPartyContext($id: UUID!) {
    profiles { profile(id: $id) { id name type thirdparty { context } } }
  }
`

const { data: configData, refresh } = useAsyncQuery<{
  configurations: { configuration: HubSpotConfig | null }
}>('hubspot-config', getConfigGql, {}, { server: false })

const token = ref('')
const tokenChanged = ref(false)
const associationTypeId = ref<number | null>(279)
const associationCategory = ref('HUBSPOT_DEFINED')
const listIds = ref<string[]>([])
const subscriptionIds = ref<string[]>([])
const genericExpression = ref('')
const organizationExpression = ref('')
const hasStoredToken = ref(false)
const saving = ref(false)

function load() {
  const config = configData.value?.configurations?.configuration
  if (!config) return
  const value = config.value ?? {}
  hasStoredToken.value = !!value.token
  token.value = value.token ? SECRET_MASK : ''
  tokenChanged.value = false
  associationTypeId.value = value.contactToCompanyAssociationTypeId ?? 279
  associationCategory.value = value.contactToCompanyAssociationCategory || 'HUBSPOT_DEFINED'
  listIds.value = value.listIds ?? []
  subscriptionIds.value = value.subscriptionIds ?? []
  genericExpression.value = value.expressions?.generic ?? ''
  organizationExpression.value = value.expressions?.organization ?? ''
}

watch(configData, load)
onMounted(load)

function onTokenInput() {
  tokenChanged.value = true
}

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const value: HubSpotValue = {
      contactToCompanyAssociationTypeId: Number(associationTypeId.value ?? 279),
      contactToCompanyAssociationCategory: associationCategory.value,
      listIds: listIds.value,
      subscriptionIds: subscriptionIds.value,
      expressions: {
        generic: genericExpression.value,
        organization: organizationExpression.value,
      },
    }
    if (tokenChanged.value) {
      value.token = token.value
    } else if (hasStoredToken.value) {
      const existing = configData.value?.configurations?.configuration?.value
      if (existing?.token) value.token = existing.token
    }

    await mutation(setConfigGql, {
      configuration: {
        key: 'hubspot',
        description: 'HubSpot Integration Configuration',
        public: false,
        value,
        permissions: [],
      },
    })
    toast.success('HubSpot configuration saved')
    await refresh()
  } catch (e) {
    toast.error(`Failed to save: ${e instanceof Error ? e.message : String(e)}`)
  } finally {
    saving.value = false
  }
}

// ── Sync ────────────────────────────────────────────────────────────────
const syncingAll = ref(false)
const syncingProfile = ref(false)

async function syncAll() {
  if (syncingAll.value) return
  syncingAll.value = true
  try {
    await mutation(syncAllGql)
    toast.success('Sync started for all profiles')
  } catch (e) {
    toast.error(`Failed to start sync: ${e instanceof Error ? e.message : String(e)}`)
  } finally {
    syncingAll.value = false
  }
}

async function syncProfile() {
  if (!selectedProfileId.value || syncingProfile.value) return
  syncingProfile.value = true
  try {
    await mutation(syncProfileGql, { profileId: selectedProfileId.value })
    toast.success(`Sync started for ${selectedProfileName.value || 'profile'}`)
  } catch (e) {
    toast.error(`Failed to start sync: ${e instanceof Error ? e.message : String(e)}`)
  } finally {
    syncingProfile.value = false
  }
}

// ── Preview ────────────────────────────────────────────────────────────
const profileTypes = new Map<string, string>()
const profileNames = new Map<string, string>()
const selectedProfileId = ref<string | null>(null)
const selectedProfileName = computed(() => selectedProfileId.value ? profileNames.get(selectedProfileId.value) ?? '' : '')
const selectedProfileType = computed(() => selectedProfileId.value ? profileTypes.get(selectedProfileId.value) ?? '' : '')
const profileContext = ref<unknown>(null)
const profileContextText = ref('')
const previewOutput = ref('')
const previewError = ref('')
const loadingContext = ref(false)

async function onProfileSearch(q: string): Promise<SelectOption[]> {
  if (!q || q.length < 2) return []
  try {
    const result = await query<{
      search?: { search?: { documents?: Array<{ profile?: { id: string, name?: string, type?: string } | null }> } }
    }>(searchProfilesGql, { query: q, limit: 20, offset: 0 })
    const documents = result?.search?.search?.documents ?? []
    const options: SelectOption[] = []
    for (const doc of documents) {
      if (!doc.profile) continue
      const { id, name, type } = doc.profile
      if (name) profileNames.set(id, name)
      if (type) profileTypes.set(id, type)
      options.push({ value: id, label: name ? `${name} · ${type ?? '—'}` : id })
    }
    return options
  } catch {
    return []
  }
}

async function loadProfileContext(id: string) {
  loadingContext.value = true
  profileContext.value = null
  profileContextText.value = ''
  previewOutput.value = ''
  previewError.value = ''
  try {
    const result = await query<{
      profiles?: { profile?: { id: string, name?: string, type?: string, thirdparty?: { context?: unknown } | null } | null }
    }>(getProfileContextGql, { id })
    const profile = result?.profiles?.profile
    if (profile) {
      if (profile.name) profileNames.set(profile.id, profile.name)
      if (profile.type) profileTypes.set(profile.id, profile.type)
    }
    const ctx = profile?.thirdparty?.context ?? null
    profileContext.value = ctx
    profileContextText.value = ctx ? JSON.stringify(ctx, null, 2) : ''
    if (ctx) await evaluatePreview()
  } catch (e) {
    previewError.value = e instanceof Error ? e.message : String(e)
  } finally {
    loadingContext.value = false
  }
}

watch(selectedProfileId, (id) => {
  if (id) loadProfileContext(id)
  else {
    profileContext.value = null
    profileContextText.value = ''
    previewOutput.value = ''
    previewError.value = ''
  }
})

async function evaluatePreview() {
  previewOutput.value = ''
  previewError.value = ''
  if (!profileContext.value) {
    previewError.value = 'No profile context loaded'
    return
  }
  const expression = selectedProfileType.value === 'ORGANIZATION'
    ? organizationExpression.value
    : genericExpression.value
  if (!expression.trim()) {
    previewError.value = `No ${selectedProfileType.value === 'ORGANIZATION' ? 'organization' : 'generic'} expression defined`
    return
  }
  try {
    const result = await jsonata(expression).evaluate(profileContext.value)
    previewOutput.value = result === undefined ? '' : JSON.stringify(result, null, 2)
  } catch (e) {
    previewError.value = e instanceof Error ? e.message : String(e)
  }
}

watch([genericExpression, organizationExpression], () => {
  if (profileContext.value) evaluatePreview()
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Integrations', 'HubSpot')"
        title="HubSpot"
        subtitle="Sync Bosca profiles to HubSpot contacts and companies"
      >
        <template #actions>
          <Button
            size="sm"
            icon="link"
            :disabled="syncingAll"
            @click="syncAll"
          >
            {{ syncingAll ? 'Starting…' : 'Sync All' }}
          </Button>
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

    <div class="hs-stack">
      <SectionCard title="General" padded>
        <div class="form-stack">
          <TextInput
            v-model="token"
            type="password"
            label="API Token"
            icon="lock"
            placeholder="Paste a HubSpot private app token"
            @blur="onTokenInput"
          />
          <NumberInput
            v-model="associationTypeId"
            label="Contact ↔ Company Association Type ID"
            :step="1"
          />
          <TextInput
            v-model="associationCategory"
            label="Contact ↔ Company Association Category"
          />
          <div class="field">
            <label class="field-label">Auto-Add to List IDs</label>
            <TagInput v-model="listIds" placeholder="HubSpot list IDs…" />
            <p class="field-help">New contacts are automatically added to these HubSpot lists.</p>
          </div>
          <div class="field">
            <label class="field-label">Auto-Subscribe to Subscription IDs</label>
            <TagInput v-model="subscriptionIds" placeholder="HubSpot subscription IDs…" />
            <p class="field-help">New contacts are automatically subscribed to these communication preferences.</p>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="JSONata Expressions" subtitle="Map Bosca profiles to HubSpot contacts" padded>
        <div class="form-stack">
          <div class="field">
            <label class="field-label">Generic Profile Expression</label>
            <CodeEditor v-model="genericExpression" language="javascript" :rows="12" />
          </div>
          <div class="field">
            <label class="field-label">Organization Profile Expression</label>
            <CodeEditor v-model="organizationExpression" language="javascript" :rows="12" />
          </div>
        </div>
      </SectionCard>

      <SectionCard title="Preview" subtitle="Evaluate expressions against a real profile" padded>
        <div class="form-stack">
          <Select
            v-model="selectedProfileId"
            label="Search Profiles"
            placeholder="Type to search profiles…"
            searchable
            :accent="accent"
            :on-search="onProfileSearch"
          />

          <div v-if="selectedProfileId" class="preview-meta">
            <span class="preview-meta-label">Selected:</span>
            <span class="preview-meta-name">{{ selectedProfileName || selectedProfileId }}</span>
            <Badge v-if="selectedProfileType" :color="selectedProfileType === 'ORGANIZATION' ? '#7c8aff' : '#34d99a'">
              {{ selectedProfileType }}
            </Badge>
            <span class="preview-meta-spacer" />
            <Button
              size="sm"
              icon="refresh"
              :disabled="loadingContext"
              @click="loadProfileContext(selectedProfileId!)"
            >
              Refresh
            </Button>
            <Button
              size="sm"
              icon="link"
              :disabled="syncingProfile"
              @click="syncProfile"
            >
              {{ syncingProfile ? 'Syncing…' : 'Sync this profile' }}
            </Button>
          </div>

          <div v-if="selectedProfileId" class="preview-grid">
            <div class="field">
              <label class="field-label">Profile Context (input)</label>
              <CodeEditor
                v-model="profileContextText"
                language="json"
                :rows="16"
                readonly
              />
            </div>
            <div class="field">
              <label class="field-label">Expression Output</label>
              <CodeEditor
                v-if="!previewError"
                v-model="previewOutput"
                language="json"
                :rows="16"
                readonly
              />
              <div v-else class="preview-error">
                <Icon name="alert" :size="14" color="var(--err)" />
                <span>{{ previewError }}</span>
              </div>
            </div>
          </div>
        </div>
      </SectionCard>

    </div>
  </PageShell>
</template>

<style scoped>
.hs-stack {
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

.preview-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}

.preview-meta-label {
  font-size: 12px;
  font-weight: 540;
  color: var(--fg-2);
}

.preview-meta-name {
  font-size: 13px;
  color: var(--fg-0);
}

.preview-meta-spacer {
  flex: 1;
}

.preview-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.preview-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px 14px;
  background: color-mix(in oklch, var(--err) 8%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--err) 38%, transparent);
  border-radius: var(--r-sm);
  color: var(--err);
  font-size: 12.5px;
  font-family: var(--font-mono);
  white-space: pre-wrap;
}

@media (max-width: 900px) {
  .preview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
