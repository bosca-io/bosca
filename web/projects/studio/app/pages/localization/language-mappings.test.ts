import { flushPromises, mount } from '@vue/test-utils'
import { print, type DocumentNode } from 'graphql'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LanguageMappingsPage from './language-mappings.vue'

const mutation = vi.fn().mockResolvedValue({})
const refresh = vi.fn().mockResolvedValue(undefined)
let mappingsDocument: DocumentNode

vi.mock('@bosca/auth-client-browser', () => ({
  useAuth: () => ({ auth: { getAuthHeaders: vi.fn() } }),
}))

vi.stubGlobal('useGraphQL', () => ({
  mutation,
  useAsyncQuery: (_key: string, document: DocumentNode) => {
    mappingsDocument = document
    return {
      data: ref({
        languages: {
          all: [
            { tag: 'en', name: 'English', localName: 'English' },
            { tag: 'en-US', name: 'English (United States)', localName: 'English (United States)' },
            { tag: 'es', name: 'Spanish', localName: 'Español' },
          ],
          resolutionContexts: [
            {
              id: 'context-bibles',
              key: 'bibles',
              name: 'Bibles',
              description: 'Bible language aliases',
              fallbackLanguageTag: 'eng',
              mappings: [
                { contextId: 'context-bibles', sourceLanguageTag: 'en', resolvedLanguageTag: 'eng' },
                { contextId: 'context-bibles', sourceLanguageTag: 'en-US', resolvedLanguageTag: 'eng' },
                { contextId: 'context-bibles', sourceLanguageTag: 'es', resolvedLanguageTag: 'spa' },
              ],
            },
          ],
        },
      }),
      status: ref('success'),
      refresh,
    }
  },
}))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#34d99a' }))
vi.stubGlobal('buildBreadcrumb', (...parts: string[]) => parts)

const stubs = {
  PageShell: { template: '<div><slot name="header" /><slot /></div>' },
  PageHeader: {
    props: ['title'],
    template: '<header><h1>{{ title }}</h1><slot name="actions" /></header>',
  },
  Button: {
    props: ['disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  Icon: { template: '<span />' },
  SearchInput: {
    props: ['modelValue', 'placeholder'],
    emits: ['update:modelValue'],
    template: `<input :value="modelValue" :placeholder="placeholder" @input="$emit('update:modelValue', $event.target.value)">`,
  },
  Select: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template: `<input :value="modelValue ?? ''" @input="$emit('update:modelValue', $event.target.value)">`,
  },
  SectionCard: {
    props: ['title'],
    template: '<section :data-title="title"><slot name="right" /><slot /></section>',
  },
  Modal: {
    props: ['title'],
    template: '<div class="test-modal" :data-title="title"><slot /><slot name="footer" /></div>',
  },
  ConfirmModal: {
    emits: ['confirm'],
    template: '<div><slot /><button class="confirm-action" @click="$emit(\'confirm\')">Confirm</button></div>',
  },
}

function mountPage() {
  return mount(LanguageMappingsPage, {
    global: {
      stubs,
      mocks: { buildBreadcrumb: (...parts: string[]) => parts },
    },
  })
}

describe('Localization language mappings page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mutation.mockResolvedValue({})
    refresh.mockResolvedValue(undefined)
  })

  it('loads mappings on their own page and groups locale variants by parent language', async () => {
    const wrapper = mountPage()
    const query = print(mappingsDocument)

    expect(query).toContain('resolutionContexts')
    expect(wrapper.text()).toContain('Language mappings')

    const english = wrapper.find('[data-language-group="en"]')
    expect(english.exists()).toBe(true)
    expect(english.text()).toContain('English')
    expect(english.text()).toContain('en')
    expect(english.text()).toContain('en-US')
    expect(english.findAll('[data-mapping-source]')).toHaveLength(2)
    expect(english.find('[data-mapping-source="en-US"]').text()).toContain('eng')

    const spanish = wrapper.find('[data-language-group="es"]')
    expect(spanish.text()).toContain('Spanish')
    expect(spanish.findAll('[data-mapping-source]')).toHaveLength(0)
    await spanish.find('.language-group-toggle').trigger('click')
    expect(spanish.findAll('[data-mapping-source]')).toHaveLength(1)
  })

  it('adds a locale mapping from within its parent language group', async () => {
    const wrapper = mountPage()
    const english = wrapper.find('[data-language-group="en"]')
    const addButton = english.findAll('button').find(button => button.text() === 'Add')

    await addButton!.trigger('click')
    await wrapper.find('.mapping-source-input').setValue('en-GB')
    await wrapper.find('.mapping-target-input').setValue('eng')
    const saveButton = wrapper.findAll('button').find(button => button.text() === 'Add mapping')
    await saveButton!.trigger('click')
    await flushPromises()

    expect(print(mutation.mock.calls[0]![0])).toContain('setLanguageTagMapping')
    expect(mutation.mock.calls[0]![1]).toEqual({
      contextId: 'context-bibles',
      input: { sourceLanguageTag: 'en-GB', resolvedLanguageTag: 'eng' },
    })
    expect(refresh).toHaveBeenCalled()
  })

  it('filters groups and mappings by locale tag', async () => {
    const wrapper = mountPage()

    await wrapper.find('input[placeholder="Search languages or mappings…"]').setValue('en-US')

    expect(wrapper.findAll('[data-language-group]')).toHaveLength(1)
    const english = wrapper.find('[data-language-group="en"]')
    expect(english.findAll('[data-mapping-source]')).toHaveLength(2)
    expect(english.text()).toContain('en-US')
  })

  it('deletes a mapping from its language group', async () => {
    const wrapper = mountPage()
    const mapping = wrapper.find('[data-mapping-source="en-US"]')

    await mapping.find('button[aria-label="Delete mapping"]').trigger('click')
    await wrapper.find('.confirm-action').trigger('click')
    await flushPromises()

    expect(print(mutation.mock.calls[0]![0])).toContain('deleteLanguageTagMapping')
    expect(mutation.mock.calls[0]![1]).toEqual({
      contextId: 'context-bibles',
      sourceLanguageTag: 'en-US',
    })
  })
})
