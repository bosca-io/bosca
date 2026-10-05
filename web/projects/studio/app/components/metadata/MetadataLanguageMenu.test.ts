import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import MetadataLanguageMenu from './MetadataLanguageMenu.vue'

const platformLanguages = ref([
  { tag: 'en', name: 'English' },
  { tag: 'es', name: 'Spanish' },
  { tag: 'fr', name: 'French' },
])

vi.stubGlobal('useLanguage', () => ({
  languagesRef: platformLanguages,
  getLanguage: (tag: string) =>
    platformLanguages.value.find(l => l.tag.toLowerCase() === tag.toLowerCase()),
}))

const mockMutation = vi.fn()
vi.stubGlobal('useGraphQL', () => ({ mutation: mockMutation }))

const stubs = {
  Icon: { template: '<i />' },
  Button: {
    template: '<button class="trigger" @click="$emit(\'click\')"><slot /></button>',
    props: ['icon', 'size', 'title'],
    emits: ['click'],
  },
  OverflowMenu: {
    template: `<div>
      <slot :toggle="() => {}" />
      <button v-for="item in items" :key="item.id" class="menu-item" :data-id="item.id" :disabled="item.disabled" @click="$emit('select', item.id)">{{ item.label }}</button>
    </div>`,
    props: ['items'],
    emits: ['select'],
  },
  ConfirmModal: {
    template: '<div class="confirm-modal"><span class="confirm-title">{{ title }}</span><button class="confirm-btn" @click="$emit(\'confirm\')" /></div>',
    props: ['title', 'subtitle', 'confirmLabel', 'loading'],
    emits: ['close', 'confirm'],
  },
  AddLanguageVariantModal: {
    template: '<div class="custom-modal" />',
    props: ['metadataId', 'metadataVersion', 'existingLanguages', 'accent'],
    emits: ['close', 'created'],
  },
}

const variants = [
  { id: 'meta-en', languageTag: 'en', name: 'My Doc' },
  { id: 'meta-es', languageTag: 'es', name: 'Mi Documento' },
]

function mountMenu(props: Record<string, unknown> = {}) {
  return mount(MetadataLanguageMenu, {
    props: {
      metadataId: 'meta-en',
      metadataVersion: 1,
      languageTag: 'en',
      variants,
      urlPrefix: '/cms/editor/',
      ...props,
    },
    global: { stubs },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('MetadataLanguageMenu', () => {
  it('shows the current language name on the trigger', () => {
    const wrapper = mountMenu()
    expect(wrapper.find('.trigger').text()).toBe('English')
  })

  it('falls back to the tag when the language is not in the catalog', () => {
    const wrapper = mountMenu({ languageTag: 'xx-custom' })
    expect(wrapper.find('.trigger').text()).toBe('xx-custom')
  })

  it('lists every platform language as goto or create', () => {
    const wrapper = mountMenu()
    const ids = wrapper.findAll('.menu-item').map(i => i.attributes('data-id'))
    expect(ids).toContain('goto:meta-en')
    expect(ids).toContain('goto:meta-es')
    expect(ids).toContain('create:fr')
    expect(ids).toContain('custom')
  })

  it('disables the current variant entry', () => {
    const wrapper = mountMenu()
    const current = wrapper.find('.menu-item[data-id="goto:meta-en"]')
    expect(current.attributes('disabled')).toBeDefined()
    const other = wrapper.find('.menu-item[data-id="goto:meta-es"]')
    expect(other.attributes('disabled')).toBeUndefined()
  })

  it('keeps variants reachable when their language is missing from the catalog', () => {
    const wrapper = mountMenu({
      variants: [...variants, { id: 'meta-xx', languageTag: 'xx-custom', name: 'Custom' }],
    })
    const ids = wrapper.findAll('.menu-item').map(i => i.attributes('data-id'))
    expect(ids).toContain('goto:meta-xx')
  })

  it('asks for confirmation before creating a variant', async () => {
    const wrapper = mountMenu()
    await wrapper.find('.menu-item[data-id="create:fr"]').trigger('click')
    expect(wrapper.find('.confirm-modal').exists()).toBe(true)
    expect(wrapper.find('.confirm-title').text()).toContain('French')
    expect(mockMutation).not.toHaveBeenCalled()
  })

  it('creates the variant against the parent metadata when set', async () => {
    mockMutation.mockResolvedValue({ content: { metadata: { addLanguageVariant: { id: 'meta-fr' } } } })
    const wrapper = mountMenu({ parentId: 'meta-root' })
    await wrapper.find('.menu-item[data-id="create:fr"]').trigger('click')
    await wrapper.find('.confirm-btn').trigger('click')
    expect(mockMutation).toHaveBeenCalledWith(expect.anything(), {
      id: 'meta-root',
      version: 1,
      languageTag: 'fr',
    })
  })

  it('creates against the metadata itself without a parent', async () => {
    mockMutation.mockResolvedValue({ content: { metadata: { addLanguageVariant: { id: 'meta-fr' } } } })
    const wrapper = mountMenu()
    await wrapper.find('.menu-item[data-id="create:fr"]').trigger('click')
    await wrapper.find('.confirm-btn').trigger('click')
    expect(mockMutation).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ id: 'meta-en' }))
  })

  it('opens the custom language modal', async () => {
    const wrapper = mountMenu()
    await wrapper.find('.menu-item[data-id="custom"]').trigger('click')
    expect(wrapper.find('.custom-modal').exists()).toBe(true)
  })
})
