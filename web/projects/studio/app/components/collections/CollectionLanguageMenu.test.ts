import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import CollectionLanguageMenu from './CollectionLanguageMenu.vue'

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
}

function mountMenu(props: Record<string, unknown> = {}) {
  return mount(CollectionLanguageMenu, {
    props: {
      baseTag: 'en',
      variantTags: ['es'],
      currentTag: 'en',
      ...props,
    },
    global: { stubs },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('CollectionLanguageMenu', () => {
  it('shows the current language name on the trigger', () => {
    const wrapper = mountMenu()
    expect(wrapper.find('.trigger').text()).toBe('English')
  })

  it('shows the variant language when bound to one', () => {
    const wrapper = mountMenu({ currentTag: 'es' })
    expect(wrapper.find('.trigger').text()).toBe('Spanish')
  })

  it('lists base and variant languages as goto, others as create', () => {
    const wrapper = mountMenu()
    const ids = wrapper.findAll('.menu-item').map(i => i.attributes('data-id'))
    expect(ids).toContain('goto:en')
    expect(ids).toContain('goto:es')
    expect(ids).toContain('create:fr')
  })

  it('disables the currently bound language', () => {
    const wrapper = mountMenu({ currentTag: 'es' })
    expect(wrapper.find('.menu-item[data-id="goto:es"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.menu-item[data-id="goto:en"]').attributes('disabled')).toBeUndefined()
  })

  it('keeps variants reachable when their language is missing from the catalog', () => {
    const wrapper = mountMenu({ variantTags: ['es', 'xx-custom'] })
    const ids = wrapper.findAll('.menu-item').map(i => i.attributes('data-id'))
    expect(ids).toContain('goto:xx-custom')
  })

  it('emits select for existing languages', async () => {
    const wrapper = mountMenu()
    await wrapper.find('.menu-item[data-id="goto:es"]').trigger('click')
    expect(wrapper.emitted('select')).toEqual([['es']])
  })

  it('asks for confirmation before emitting create', async () => {
    const wrapper = mountMenu()
    await wrapper.find('.menu-item[data-id="create:fr"]').trigger('click')
    expect(wrapper.emitted('create')).toBeUndefined()
    expect(wrapper.find('.confirm-title').text()).toContain('French')
    await wrapper.find('.confirm-btn').trigger('click')
    expect(wrapper.emitted('create')).toEqual([['fr']])
  })
})
