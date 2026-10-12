<script setup lang="ts">
import type { DocumentNode } from 'graphql'
import { BibleContentUse, BibleSourceKind } from '~/types/graphql'
import {
  configurationsQuery, sourcesQuery, catalogQuery, audioTargetsQuery,
  saveConfigurationMutation, testConfigurationMutation, addBibleMutation, addAudioMutation,
  refreshSourceMutation, removeSourceMutation, removeConfigurationMutation,
  type ProviderConfiguration, type ProviderCatalog, type ProviderSource, type AudioTarget, type BibleProviderResult,
} from '~/utils/bibleProviders'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, query, mutation } = useGraphQL()
const toast = useToast()
const busy = ref(false)
const configurationId = ref<string | null>(null)
const { data, error, status, refresh } = useAsyncQuery<BibleProviderResult<{
  providers: string[]
  configurations: ProviderConfiguration[]
}>>('bible-provider-configurations', configurationsQuery, {}, { server: false })
const configurations = computed(() => data.value?.content.bibleProviders.configurations ?? [])
const providers = computed(() => data.value?.content.bibleProviders.providers ?? [])
const selected = computed(() => configurations.value.find(config => config.id === configurationId.value))
watch(configurations, values => {
  if (!values.some(config => config.id === configurationId.value)) configurationId.value = values[0]?.id ?? null
}, { immediate: true })
const { data: sourceData, status: sourceStatus, error: sourceError, refresh: refreshSources } = useAsyncQuery<BibleProviderResult<{
  sources: ProviderSource[]
}>>('bible-provider-sources', sourcesQuery, { configurationId: computed(() => configurationId.value ?? undefined) }, { server: false })
const sources = computed(() => (sourceData.value?.content.bibleProviders.sources ?? []).filter(source => source.configurationId === configurationId.value))

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

async function perform(document: DocumentNode, variables: Record<string, unknown>, success: string): Promise<boolean> {
  if (busy.value) return false
  busy.value = true
  try {
    await mutation(document, variables)
    toast.success(success)
    return true
  } catch (error: unknown) {
    toast.error(message(error))
    return false
  } finally {
    busy.value = false
  }
}

const showConfiguration = ref(false)
const editing = ref<ProviderConfiguration | null>(null)
const name = ref('')
const provider = ref<string | null>(null)
const enabled = ref(true)
const apiKey = ref('')
const canSave = computed(() => !busy.value && !!name.value.trim() && !!provider.value
  && (!!apiKey.value.trim() || !!editing.value?.hasApiKey))

function editConfiguration(config: ProviderConfiguration | null) {
  editing.value = config
  name.value = config?.name ?? ''
  provider.value = config?.provider ?? providers.value[0] ?? null
  enabled.value = config?.enabled ?? true
  apiKey.value = ''
  showConfiguration.value = true
}

function closeConfiguration() {
  if (busy.value) return
  showConfiguration.value = false
  editing.value = null
  apiKey.value = ''
}

async function saveConfiguration() {
  if (!canSave.value) return
  if (await perform(saveConfigurationMutation, { input: {
    id: editing.value?.id ?? null, name: name.value.trim(), provider: provider.value,
    enabled: enabled.value, apiKey: apiKey.value.trim() || null,
  } }, 'Bible provider configuration saved')) {
    closeConfiguration()
    await refresh()
    await refreshSources()
  }
}

const kind = ref<string | null>(BibleSourceKind.Text)
const search = ref('')
const catalog = ref<ProviderCatalog[]>([])
const offset = ref(0)
const limit = 20
const catalogLoading = ref(false)
const catalogError = ref('')
let catalogRequest = 0

async function loadCatalog(nextOffset = 0) {
  const request = ++catalogRequest
  catalog.value = []
  catalogError.value = ''
  offset.value = nextOffset
  if (!selected.value?.enabled) { catalogLoading.value = false; return }
  catalogLoading.value = true
  try {
    const result = await query<BibleProviderResult<{ catalog: ProviderCatalog[] }>>(catalogQuery, {
      configurationId: selected.value.id, kind: kind.value, query: search.value.trim() || null, offset: nextOffset, limit,
    })
    if (request === catalogRequest) catalog.value = result.content.bibleProviders.catalog
  } catch (error: unknown) {
    if (request === catalogRequest) catalogError.value = message(error)
  } finally {
    if (request === catalogRequest) catalogLoading.value = false
  }
}

const registration = ref<{ configurationId: string; entry: ProviderCatalog } | null>(null)
const variant = ref('')
const allowedUses = ref<BibleContentUse[]>([])
const audioTargets = ref<AudioTarget[]>([])
const audioTarget = ref<string | null>(null)
const targetsLoading = ref(false)
const targetsError = ref('')
let targetRequest = 0
const textUses = [
  { value: BibleContentUse.Display, label: 'Display' },
  { value: BibleContentUse.AiContext, label: 'AI context' },
  { value: BibleContentUse.TextToSpeech, label: 'Text to speech' },
  { value: BibleContentUse.Indexing, label: 'Indexing' },
  { value: BibleContentUse.Export, label: 'Export' },
]
const useOptions = computed(() => registration.value?.entry.kind === BibleSourceKind.Audio
  ? [{ value: BibleContentUse.Playback, label: 'Playback' }] : textUses)
const chosenTarget = computed(() => audioTarget.value === null ? undefined : audioTargets.value[Number(audioTarget.value)])
const canRegister = computed(() => !busy.value && !!registration.value && !targetsLoading.value
  && (registration.value.entry.kind === BibleSourceKind.Text ? !!variant.value.trim() : !!chosenTarget.value))

function closeRegistration() {
  if (busy.value) return
  targetRequest++
  registration.value = null
  audioTargets.value = []
  audioTarget.value = null
  allowedUses.value = []
  targetsLoading.value = false
}

async function registerEntry(entry: ProviderCatalog) {
  if (!selected.value?.enabled || busy.value) return
  closeRegistration()
  registration.value = { configurationId: selected.value.id, entry }
  variant.value = ''
  targetsError.value = ''
  if (entry.kind !== BibleSourceKind.Audio) return
  const request = ++targetRequest
  targetsLoading.value = true
  try {
    const result = await query<BibleProviderResult<{ audioTargets: AudioTarget[] }>>(audioTargetsQuery, {
      configurationId: selected.value.id, providerBibleId: entry.id,
    })
    if (request === targetRequest) audioTargets.value = result.content.bibleProviders.audioTargets
  } catch (error: unknown) {
    if (request === targetRequest) targetsError.value = message(error)
  } finally {
    if (request === targetRequest) targetsLoading.value = false
  }
}

function updateUse(use: BibleContentUse, checked: boolean) {
  allowedUses.value = checked ? [...allowedUses.value, use] : allowedUses.value.filter(value => value !== use)
}

async function register() {
  const current = registration.value
  if (!current || !canRegister.value) return
  const input = { configurationId: current.configurationId, providerBibleId: current.entry.id, allowedUses: allowedUses.value }
  const target = chosenTarget.value
  const saved = current.entry.kind === BibleSourceKind.Text
    ? await perform(addBibleMutation, { input: { ...input, variant: variant.value.trim() } }, 'Bible added')
    : target && await perform(addAudioMutation, { input, bibleId: target.metadataId, version: target.version, variant: target.variant }, 'Bible audio added')
  if (saved) {
    closeRegistration()
    await refreshSources()
  }
}

const removeSource = ref<ProviderSource | null>(null)
const removeConfiguration = ref<{ configuration: ProviderConfiguration; sources: ProviderSource[] } | null>(null)
const purgeSources = ref(false)

function confirmRemoveConfiguration() {
  if (!selected.value || busy.value || sourceStatus.value !== 'success') return
  removeConfiguration.value = { configuration: selected.value, sources: [...sources.value] }
  purgeSources.value = false
}

async function deleteConfiguration() {
  const target = removeConfiguration.value
  if (!target || (target.sources.length > 0 && !purgeSources.value)) return
  if (await perform(removeConfigurationMutation, {
    configurationId: target.configuration.id, purgeSources: target.sources.length > 0 && purgeSources.value,
  }, 'Bible provider configuration removed')) {
    removeConfiguration.value = null
    await refresh()
  }
}

async function deleteSource() {
  const target = removeSource.value
  if (!target) return
  if (await perform(removeSourceMutation, { sourceId: target.id, expectedVersion: target.version }, 'Bible source removed')) {
    removeSource.value = null
    await refreshSources()
  }
}

async function refreshSource(source: ProviderSource) {
  if (await perform(refreshSourceMutation, { sourceId: source.id }, 'Bible source refreshed')) await refreshSources()
}

watch([configurationId, kind, () => selected.value?.enabled], () => {
  closeRegistration()
  removeSource.value = null
  removeConfiguration.value = null
  void loadCatalog()
}, { immediate: true })
onUnmounted(() => { apiKey.value = ''; catalogRequest++; targetRequest++ })
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Integrations', 'Bible Providers')"
        title="Bible Providers"
        subtitle="Configure providers and register text Bibles and recordings">
        <template #actions>
          <Button size="sm" :disabled="busy || providers.length === 0" @click="editConfiguration(null)">Add configuration</Button>
        </template>
      </PageHeader>
    </template>

    <ClientOnly>
      <div class="bible-providers-stack">
        <p v-if="error" role="alert" class="field-error">{{ error.message }}</p>
        <p v-else-if="status === 'pending'">Loading configurations…</p>
        <SectionCard title="Configurations" padded>
          <div class="form-stack">
            <Select
              v-model="configurationId"
              label="Configuration"
              :options="configurations.map(config => ({ value: config.id, label: `${config.name}${config.enabled ? '' : ' (disabled)'}` }))"
              :disabled="busy"
              :accent="accent" />
            <p v-if="!configurations.length && status === 'success'" class="field-help">Add a configuration to browse the provider catalog.</p>
            <template v-if="selected">
              <p class="field-help">{{ selected.provider === 'API_BIBLE' ? 'API.Bible' : selected.provider }} · {{ selected.hasApiKey ? 'Key saved' : 'Key missing' }}</p>
              <div class="actions">
                <Button size="sm" :disabled="busy" @click="editConfiguration(selected)">Edit configuration</Button>
                <Button size="sm" :disabled="busy" @click="perform(testConfigurationMutation, { configurationId: selected.id }, 'Bible provider connection succeeded')">Test connection</Button>
                <Button size="sm" :disabled="busy || sourceStatus !== 'success'" @click="confirmRemoveConfiguration">Remove configuration</Button>
              </div>
            </template>
          </div>
        </SectionCard>

        <SectionCard v-if="selected" title="Catalog" padded>
          <p v-if="!selected.enabled" class="field-help">Enable this configuration to browse its catalog.</p>
          <template v-else>
            <div class="catalog-controls">
              <Select
                v-model="kind"
                label="Catalog"
                :options="[{ value: 'TEXT', label: 'Text' }, { value: 'AUDIO', label: 'Audio' }]"
                :disabled="busy"
                :accent="accent" />
              <TextInput
                v-model="search"
                label="Search catalog"
                :disabled="busy"
                @keydown.enter="loadCatalog()" />
              <Button size="sm" :disabled="busy" @click="loadCatalog()">Search</Button>
            </div>
            <p v-if="catalogError" role="alert" class="field-error">{{ catalogError }}</p>
            <p v-else-if="catalogLoading">Loading catalog…</p>
            <p v-else-if="!catalog.length" class="field-help">No catalog results.</p>
            <ul class="entries">
              <li v-for="entry in catalog" :key="entry.id" class="entry">
                <div class="entry-details">
                  <strong>{{ entry.name }}</strong>
                  <span v-if="entry.nameLocal !== entry.name">{{ entry.nameLocal }}</span>
                  <span>{{ entry.abbreviation }} · {{ entry.languages.map(language => language.name).join(', ') }}</span>
                  <p v-if="entry.description">{{ entry.description }}</p>
                  <p>{{ entry.attribution.copyright }}</p>
                  <p v-if="entry.attribution.citation">{{ entry.attribution.citation }}</p>
                </div>
                <Button size="sm" :disabled="busy" @click="registerEntry(entry)">{{ entry.kind === 'TEXT' ? 'Add Bible' : 'Add audio' }}</Button>
              </li>
            </ul>
            <div class="actions">
              <Button size="sm" :disabled="busy || catalogLoading || offset === 0" @click="loadCatalog(Math.max(0, offset - limit))">Previous</Button>
              <span class="field-help">Page {{ Math.floor(offset / limit) + 1 }}</span>
              <Button size="sm" :disabled="busy || catalogLoading || catalog.length < limit" @click="loadCatalog(offset + limit)">Next</Button>
            </div>
          </template>
        </SectionCard>

        <SectionCard v-if="selected" title="Sources" padded>
          <p v-if="sourceError" role="alert" class="field-error">{{ sourceError.message }}</p>
          <p v-else-if="sourceStatus === 'pending'">Loading sources…</p>
          <p v-else-if="!sources.length" class="field-help">No sources registered.</p>
          <ul class="entries">
            <li v-for="source in sources" :key="source.id" class="entry">
              <div class="entry-details">
                <NuxtLink :to="`/cms/metadata/${source.metadataId}`">{{ source.name }}</NuxtLink>
                <span>{{ source.kind === 'TEXT' ? 'Text' : 'Audio' }} · version {{ source.metadataVersion }} · {{ source.variant }}</span>
                <span>Permitted uses: {{ source.allowedUses.map(use => use.replaceAll('_', ' ').toLowerCase()).join(', ') || 'None' }}</span>
                <p>{{ source.attribution.copyright }}</p>
              </div>
              <div class="actions">
                <Button size="sm" :disabled="busy || !selected.enabled" @click="refreshSource(source)">Refresh</Button>
                <Button size="sm" :disabled="busy" @click="removeSource = source">Remove source</Button>
              </div>
            </li>
          </ul>
        </SectionCard>
      </div>
      <template #fallback>
        <p>Loading configurations…</p>
      </template>
    </ClientOnly>

    <Modal
      v-if="showConfiguration"
      :title="editing ? 'Edit Bible provider configuration' : 'Add Bible provider configuration'"
      :accent="accent"
      @close="closeConfiguration">
      <div class="form-stack">
        <TextInput v-model="name" label="Name" :disabled="busy" />
        <Select
          v-model="provider"
          label="Provider"
          :options="providers.map(value => ({ value, label: value === 'API_BIBLE' ? 'API.Bible' : value }))"
          :disabled="busy"
          :accent="accent" />
        <Checkbox
          v-model="enabled"
          label="Enabled"
          :disabled="busy"
          :accent="accent" />
        <TextInput
          v-model="apiKey"
          label="API key"
          type="password"
          autocomplete="new-password"
          :disabled="busy" />
        <p class="field-help">{{ editing?.hasApiKey ? 'Leave blank to keep the saved key.' : 'Enter the provider API key.' }}</p>
      </div>
      <template #footer>
        <Button size="sm" :disabled="busy" @click="closeConfiguration">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!canSave"
          @click="saveConfiguration">Save</Button>
      </template>
    </Modal>

    <Modal
      v-if="registration"
      :title="`Register ${registration.entry.name}`"
      :accent="accent"
      @close="closeRegistration">
      <div class="form-stack">
        <p>{{ registration.entry.attribution.copyright }}</p>
        <p v-if="registration.entry.attribution.citation">{{ registration.entry.attribution.citation }}</p>
        <TextInput
          v-if="registration.entry.kind === 'TEXT'"
          v-model="variant"
          label="Variant"
          placeholder="reader"
          :disabled="busy" />
        <template v-else>
          <p v-if="targetsLoading">Finding compatible Bible variants…</p>
          <p v-else-if="targetsError" role="alert" class="field-error">{{ targetsError }}</p>
          <p v-else-if="!audioTargets.length" class="field-help">No compatible Bible variants. Register the matching text Bible first.</p>
          <Select
            v-model="audioTarget"
            label="Bible variant"
            :options="audioTargets.map((target, index) => ({ value: String(index), label: `${target.name} · version ${target.version} · ${target.variant}${target.enabled ? '' : ' (disabled)'}` }))"
            :disabled="busy || targetsLoading"
            :accent="accent" />
        </template>
        <p class="field-help">Select the uses permitted by your provider agreement. Leave all unchecked to grant no use.</p>
        <Checkbox
          v-for="use in useOptions"
          :key="use.value"
          :model-value="allowedUses.includes(use.value)"
          :label="use.label"
          :disabled="busy"
          :accent="accent"
          @update:model-value="updateUse(use.value, $event)" />
      </div>
      <template #footer>
        <Button size="sm" :disabled="busy" @click="closeRegistration">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!canRegister"
          @click="register">Register</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="removeSource"
      :title="`Remove ${removeSource.name}?`"
      subtitle="Remove this provider source and its cached content."
      confirm-label="Remove"
      :loading="busy"
      @close="!busy && (removeSource = null)"
      @confirm="deleteSource" />
    <Modal
      v-if="removeConfiguration"
      :title="`Remove ${removeConfiguration.configuration.name}?`"
      :accent="accent"
      @close="!busy && (removeConfiguration = null)">
      <div class="form-stack">
        <template v-if="removeConfiguration.sources.length">
          <p>Removing this configuration also removes these {{ removeConfiguration.sources.length }} sources and their cached content:</p>
          <ul>
            <li v-for="source in removeConfiguration.sources" :key="source.id">{{ source.name }} · {{ source.variant }} · version {{ source.metadataVersion }}</li>
          </ul>
          <Checkbox
            v-model="purgeSources"
            label="Remove all listed sources"
            :disabled="busy"
            :accent="accent" />
        </template>
        <p v-else>Remove this configuration?</p>
      </div>
      <template #footer>
        <Button size="sm" :disabled="busy" @click="removeConfiguration = null">Cancel</Button>
        <Button size="sm" :disabled="busy || (removeConfiguration.sources.length > 0 && !purgeSources)" @click="deleteConfiguration">Remove configuration</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.bible-providers-stack,
.form-stack,
.entry-details {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.form-stack { max-width: 680px; }
.entry-details { gap: 4px; min-width: 0; overflow-wrap: anywhere; }
.entry-details p { margin: 0; }
.entry-details span,
.entry-details p,
.field-help { color: var(--fg-3); font-size: 12px; line-height: 1.5; }
.field-error { color: var(--err); }
.actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.catalog-controls { display: grid; grid-template-columns: minmax(140px, 1fr) minmax(180px, 2fr) auto; gap: 12px; align-items: end; }
.entries { list-style: none; padding: 0; margin: 12px 0; }
.entry { display: flex; justify-content: space-between; align-items: start; gap: 16px; padding: 14px 0; border-bottom: 1px solid var(--line); }

@media (max-width: 640px) {
  .catalog-controls { grid-template-columns: 1fr; }
  .entry { flex-direction: column; }
}
</style>
