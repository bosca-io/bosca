import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import BiblePage from './bible.vue'

const query = vi.fn()
const mutation = vi.fn()
const refresh = vi.fn()
const refreshSources = vi.fn()
const toastError = vi.fn()
const configurations = ref<unknown>(null)
const sourceData = ref<unknown>(null)
const sourceStatus = ref('success')
const configError = ref<Error | null>(null)
const config = { id: 'config-1', name: 'Scripture', provider: 'API_BIBLE', enabled: true, hasApiKey: true }
const attribution = { copyright: 'Publisher copyright', citation: 'Publisher citation', informationUrl: null }
const text = { id: 'edition-1', kind: 'TEXT', name: 'Translation', nameLocal: 'Local translation', abbreviation: 'TR', description: 'Translation description', languages: [{ name: 'English', iso: 'eng' }], attribution }
const audio = { ...text, id: 'recording-1', kind: 'AUDIO', name: 'Recording' }
const source = { id: 'source-1', configurationId: config.id, name: 'Saved translation', kind: 'TEXT', metadataId: 'metadata-1', metadataVersion: 2, variant: 'reader', version: 7, allowedUses: ['DISPLAY'], attribution }
const targets = [
  { metadataId: 'metadata-1', version: 1, variant: 'reader', name: 'Translation', enabled: true },
  { metadataId: 'metadata-1', version: 2, variant: 'study', name: 'Translation', enabled: false },
]

function operation(document: { definitions: Array<{ name?: { value: string } }> }) {
  return document.definitions[0]?.name?.value
}

vi.stubGlobal('useGraphQL', () => ({
  useAsyncQuery: (key: string) => key === 'bible-provider-configurations'
    ? { data: configurations, status: ref('success'), error: configError, refresh }
    : { data: sourceData, status: sourceStatus, error: ref(null), refresh: refreshSources },
  query,
  mutation,
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#2272f2' }))
vi.stubGlobal('useToast', () => ({ success: vi.fn(), error: toastError }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const global = {
  mocks: { buildBreadcrumb: (...parts: string[]) => parts },
  stubs: {
    PageShell: { template: '<main><slot name="header" /><slot /></main>' },
    PageHeader: { props: ['title'], template: '<header><h1>{{ title }}</h1><slot name="actions" /></header>' },
    SectionCard: { props: ['title'], template: '<section><h2>{{ title }}</h2><slot /><slot name="actions" /></section>' },
    Button: { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
    TextInput: { props: ['modelValue', 'label', 'type', 'disabled'], emits: ['update:modelValue'], template: '<label>{{ label }}<input :type="type || \'text\'" :data-field="label" :disabled="disabled" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /></label>' },
    Select: { props: ['modelValue', 'label', 'options', 'disabled'], emits: ['update:modelValue'], template: '<label>{{ label }}<select :data-field="label" :disabled="disabled" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option value="">Select</option><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>' },
    Checkbox: { props: ['modelValue', 'label', 'disabled'], emits: ['update:modelValue'], template: '<label><input type="checkbox" :data-field="label" :disabled="disabled" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />{{ label }}</label>' },
    Modal: { props: ['title'], emits: ['close'], template: '<section class="modal"><h2>{{ title }}</h2><button @click="$emit(\'close\')">Close</button><slot /><slot name="footer" /></section>' },
    ConfirmModal: { props: ['title', 'subtitle', 'loading', 'confirmLabel'], emits: ['close', 'confirm'], template: '<section class="confirm-modal"><h2>{{ title }}</h2><p>{{ subtitle }}</p><slot /><button :disabled="loading" @click="$emit(\'confirm\')">{{ confirmLabel }}</button><button @click="$emit(\'close\')">Cancel</button></section>' },
    NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  },
}

function button(wrapper: ReturnType<typeof mount>, label: string) {
  const found = wrapper.findAll('button').findLast(candidate => candidate.text() === label)
  if (!found) throw new Error(`Missing button: ${label}`)
  return found
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.clearAllMocks()
  configError.value = null
  configurations.value = { content: { bibleProviders: { providers: ['API_BIBLE'], configurations: [config] } } }
  sourceData.value = { content: { bibleProviders: { sources: [source] } } }
  sourceStatus.value = 'success'
  refresh.mockResolvedValue(undefined)
  refreshSources.mockResolvedValue(undefined)
  mutation.mockResolvedValue({ content: { bibleProviders: { saveConfiguration: config } } })
  query.mockImplementation(async document => ({ content: { bibleProviders: operation(document) === 'GetBibleProviderAudioTargets'
    ? { audioTargets: targets }
    : { catalog: [text] } } }))
})

describe('Bible provider administration', () => {
  it.each(['credentials', 'enablement'])('reloads source versions after changing %s before removal', async change => {
    mutation.mockResolvedValue({ content: { bibleProviders: { saveConfiguration: config } } })
    refreshSources.mockImplementation(async () => {
      sourceData.value = { content: { bibleProviders: { sources: [{ ...source, version: 8 }] } } }
    })
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    await button(wrapper, 'Edit configuration').trigger('click')
    if (change === 'credentials') await wrapper.get('[data-field="API key"]').setValue('replacement-key')
    else await wrapper.get('[data-field="Enabled"]').setValue(false)
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()
    await button(wrapper, 'Remove source').trigger('click')
    await button(wrapper, 'Remove').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenLastCalledWith(expect.anything(), { sourceId: source.id, expectedVersion: 8 })
  })

  it('edits configuration without returning or replacing the saved key', async () => {
    const wrapper = mount(BiblePage, { global })
    await button(wrapper, 'Edit configuration').trigger('click')
    expect(wrapper.get('[data-field="API key"]').element).toHaveProperty('value', '')
    expect(wrapper.text()).toContain('Leave blank to keep the saved key')
    await wrapper.get('[data-field="Name"]').setValue('Renamed')
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), { input: { id: config.id, name: 'Renamed', provider: 'API_BIBLE', enabled: true, apiKey: null } })
    expect(wrapper.find('.modal').exists()).toBe(false)
    expect(refresh).toHaveBeenCalledOnce()
    expect(refreshSources).toHaveBeenCalledOnce()
    expect(query).toHaveBeenLastCalledWith(expect.anything(), { configurationId: config.id, kind: 'TEXT', query: null, offset: 0, limit: 20 })
    expect(query).toHaveBeenCalledTimes(2)
  })

  it('creates configuration with a key, selects it, and clears secret drafts after cancel or save', async () => {
    const created = { ...config, id: 'config-2', name: 'New setup' }
    mutation.mockResolvedValue({ content: { bibleProviders: { saveConfiguration: created } } })
    refresh.mockImplementation(async () => {
      configurations.value = { content: { bibleProviders: { providers: ['API_BIBLE'], configurations: [config, created] } } }
    })
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    await button(wrapper, 'Add configuration').trigger('click')
    await wrapper.get('[data-field="Name"]').setValue('New setup')
    expect(button(wrapper, 'Save').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-field="API key"]').setValue('new-secret')
    await button(wrapper, 'Close').trigger('click')
    await button(wrapper, 'Add configuration').trigger('click')
    expect(wrapper.get('[data-field="API key"]').element).toHaveProperty('value', '')
    await wrapper.get('[data-field="Name"]').setValue('New setup')
    await wrapper.get('[data-field="API key"]').setValue('new-secret')
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), { input: { id: null, name: 'New setup', provider: 'API_BIBLE', enabled: true, apiKey: 'new-secret' } })
    expect(refresh).toHaveBeenCalledOnce()
    expect((wrapper.get('[data-field="Configuration"]').element as HTMLSelectElement).value).toBe(created.id)
    expect(query).toHaveBeenLastCalledWith(expect.anything(), { configurationId: created.id, kind: 'TEXT', query: null, offset: 0, limit: 20 })
    expect(refreshSources).not.toHaveBeenCalled()
  })

  it('keeps the selection and catalog when saving a configuration fails', async () => {
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    await button(wrapper, 'Add configuration').trigger('click')
    await wrapper.get('[data-field="Name"]').setValue('New setup')
    await wrapper.get('[data-field="API key"]').setValue('new-secret')
    mutation.mockRejectedValueOnce(new Error('Save failed'))
    await button(wrapper, 'Save').trigger('click')
    await flushPromises()
    expect(toastError).toHaveBeenCalledWith('Save failed')
    expect(wrapper.find('.modal').exists()).toBe(true)
    expect(refresh).not.toHaveBeenCalled()
    expect(query).toHaveBeenCalledOnce()
  })

  it('tests saved configurations without reading credentials and permits retry after failure', async () => {
    const wrapper = mount(BiblePage, { global })
    mutation.mockRejectedValueOnce(new Error('Provider unavailable'))
    await button(wrapper, 'Test connection').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), { configurationId: config.id })
    expect(toastError).toHaveBeenCalledWith('Provider unavailable')
    expect(button(wrapper, 'Test connection').attributes('disabled')).toBeUndefined()
  })

  it('keeps disabled configurations manageable without requesting a catalog', async () => {
    configurations.value = { content: { bibleProviders: { providers: ['API_BIBLE'], configurations: [{ ...config, enabled: false }] } } }
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    expect(query).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Enable this configuration to browse its catalog')
    expect(button(wrapper, 'Refresh').attributes('disabled')).toBeDefined()
    await button(wrapper, 'Test connection').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), { configurationId: config.id })
  })

  it('adds text with an explicit variant and only reviewed uses', async () => {
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    expect(wrapper.text()).toContain(attribution.copyright)
    await button(wrapper, 'Add Bible').trigger('click')
    expect(button(wrapper, 'Register').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('input[type="checkbox"]').every(input => !(input.element as HTMLInputElement).checked)).toBe(true)
    await wrapper.get('[data-field="Variant"]').setValue('reader')
    await wrapper.get('[data-field="Display"]').setValue(true)
    await button(wrapper, 'Register').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), { input: { configurationId: config.id, providerBibleId: text.id, variant: 'reader', allowedUses: ['DISPLAY'] } })
    expect(refreshSources).toHaveBeenCalledOnce()
  })

  it('allows text registration with no granted uses', async () => {
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    await button(wrapper, 'Add Bible').trigger('click')
    await wrapper.get('[data-field="Variant"]').setValue('reader')
    await button(wrapper, 'Register').trigger('click')
    await flushPromises()
    expect(mutation.mock.calls[0]?.[1].input.allowedUses).toEqual([])
  })

  it('attaches audio to the exact chosen version and disabled variant with playback only', async () => {
    query.mockImplementation(async document => ({ content: { bibleProviders: operation(document) === 'GetBibleProviderAudioTargets' ? { audioTargets: targets } : { catalog: [audio] } } }))
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    await wrapper.get('[data-field="Catalog"]').setValue('AUDIO')
    await flushPromises()
    await button(wrapper, 'Add audio').trigger('click')
    await flushPromises()
    expect(button(wrapper, 'Register').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('version 2 · study (disabled)')
    await wrapper.get('[data-field="Bible variant"]').setValue('metadata-1:2:study')
    await wrapper.get('[data-field="Playback"]').setValue(true)
    await button(wrapper, 'Register').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), { bibleId: 'metadata-1', version: 2, variant: 'study', input: { configurationId: config.id, providerBibleId: audio.id, allowedUses: ['PLAYBACK'] } })
  })

  it('discards pending audio targets when the configuration changes', async () => {
    configurations.value = { content: { bibleProviders: { providers: ['API_BIBLE'], configurations: [config, { ...config, id: 'config-2', name: 'Another setup' }] } } }
    let resolveTargets: (value: unknown) => void = () => {}
    query.mockImplementation(async document => ({ content: { bibleProviders: operation(document) === 'GetBibleProviderAudioTargets'
      ? await new Promise(resolve => { resolveTargets = resolve })
      : { catalog: [audio] } } }))
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    await button(wrapper, 'Add audio').trigger('click')
    expect(wrapper.text()).toContain('Finding compatible Bible variants')
    await wrapper.get('[data-field="Configuration"]').setValue('config-2')
    await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(false)
    resolveTargets({ audioTargets: targets })
    await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(false)
    expect(mutation).not.toHaveBeenCalled()
  })

  it('blocks audio registration when there are no compatible targets or lookup fails', async () => {
    query.mockImplementation(async document => {
      if (operation(document) === 'GetBibleProviderAudioTargets') return { content: { bibleProviders: { audioTargets: [] } } }
      return { content: { bibleProviders: { catalog: [audio] } } }
    })
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    await button(wrapper, 'Add audio').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('No compatible Bible variants')
    expect(button(wrapper, 'Register').attributes('disabled')).toBeDefined()
    await button(wrapper, 'Close').trigger('click')
    query.mockRejectedValueOnce(new Error('Target lookup failed'))
    await button(wrapper, 'Add audio').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Target lookup failed')
    expect(button(wrapper, 'Register').attributes('disabled')).toBeDefined()
    expect(mutation).not.toHaveBeenCalled()
  })

  it('refreshes and removes a source with its optimistic version after confirmation', async () => {
    const wrapper = mount(BiblePage, { global })
    await button(wrapper, 'Refresh').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenLastCalledWith(expect.anything(), { sourceId: source.id })
    await button(wrapper, 'Remove source').trigger('click')
    expect(mutation).toHaveBeenCalledTimes(1)
    await button(wrapper, 'Remove').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenLastCalledWith(expect.anything(), { sourceId: source.id, expectedVersion: 7 })
  })

  it('warns that removing a text source deletes its Bible variant but not when removing a recording', async () => {
    sourceData.value = { content: { bibleProviders: { sources: [{ ...source, kind: 'AUDIO', name: 'Recording' }] } } }
    const audio = mount(BiblePage, { global })
    await flushPromises()
    await button(audio, 'Remove source').trigger('click')
    expect(audio.get('.confirm-modal').text()).toContain('Remove this recording and its cached audio.')
    audio.unmount()

    sourceData.value = { content: { bibleProviders: { sources: [source] } } }
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    await button(wrapper, 'Remove source').trigger('click')
    expect(wrapper.get('.confirm-modal').text()).toContain(`This also deletes the ${source.variant} Bible variant`)
  })

  it('requires a separate explicit confirmation to remove configuration and its sources', async () => {
    const wrapper = mount(BiblePage, { global })
    await button(wrapper, 'Remove configuration').trigger('click')
    expect(wrapper.text()).toContain('Saved translation')
    expect(button(wrapper, 'Remove configuration').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-field="Remove all listed sources"]').setValue(true)
    const confirmations = wrapper.findAll('button').filter(candidate => candidate.text() === 'Remove configuration')
    await confirmations[confirmations.length - 1]?.trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), { configurationId: config.id, purgeSources: true })
  })

  it('removes an empty configuration without purging and waits for its source list', async () => {
    sourceStatus.value = 'pending'
    sourceData.value = null
    const wrapper = mount(BiblePage, { global })
    expect(button(wrapper, 'Remove configuration').attributes('disabled')).toBeDefined()
    sourceStatus.value = 'success'
    sourceData.value = { content: { bibleProviders: { sources: [] } } }
    await flushPromises()
    await button(wrapper, 'Remove configuration').trigger('click')
    const confirmations = wrapper.findAll('button').filter(candidate => candidate.text() === 'Remove configuration')
    await confirmations[confirmations.length - 1]?.trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), { configurationId: config.id, purgeSources: false })
  })

  it('uses offset pagination and search and rejects a delayed result after filters change', async () => {
    let resolveOld: (value: unknown) => void = () => {}
    query.mockResolvedValueOnce({ content: { bibleProviders: { catalog: Array.from({ length: 20 }, (_, i) => ({ ...text, id: `edition-${i}` })) } } })
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    await button(wrapper, 'Next').trigger('click')
    await flushPromises()
    expect(query).toHaveBeenLastCalledWith(expect.anything(), { configurationId: config.id, kind: 'TEXT', query: null, offset: 20, limit: 20 })
    query.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    await wrapper.get('[data-field="Search catalog"]').setValue('old search')
    await button(wrapper, 'Search').trigger('click')
    query.mockResolvedValueOnce({ content: { bibleProviders: { catalog: [audio] } } })
    await wrapper.get('[data-field="Catalog"]').setValue('AUDIO')
    await flushPromises()
    expect(query).toHaveBeenLastCalledWith(expect.anything(), { configurationId: config.id, kind: 'AUDIO', query: 'old search', offset: 0, limit: 20 })
    resolveOld({ content: { bibleProviders: { catalog: [text] } } })
    await flushPromises()
    expect(wrapper.findAll('button').some(candidate => candidate.text() === 'Add Bible')).toBe(false)
    expect(button(wrapper, 'Add audio').exists()).toBe(true)
  })

  it('shows query failures and keeps registration retryable when the provider fails', async () => {
    const wrapper = mount(BiblePage, { global })
    await flushPromises()
    await button(wrapper, 'Add Bible').trigger('click')
    await wrapper.get('[data-field="Variant"]').setValue('reader')
    mutation.mockRejectedValueOnce(new Error('Registration failed'))
    await button(wrapper, 'Register').trigger('click')
    await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(true)
    expect(toastError).toHaveBeenCalledWith('Registration failed')
    await button(wrapper, 'Close').trigger('click')
    query.mockRejectedValueOnce(new Error('Catalog failed'))
    await button(wrapper, 'Search').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Catalog failed')
    configError.value = new Error('Administration denied')
    await flushPromises()
    expect(wrapper.text()).toContain('Administration denied')
  })
})
