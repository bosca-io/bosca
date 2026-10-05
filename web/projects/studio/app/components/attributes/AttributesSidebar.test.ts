import { describe, it, expect, vi } from 'vitest'
import { ref, defineComponent, h, Suspense, nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import AttributesSidebar from './AttributesSidebar.vue'

vi.stubGlobal('useGraphQL', () => ({
  query: vi.fn().mockResolvedValue(null),
  mutation: vi.fn(),
  useAsyncQuery: () => ({ data: ref({ content: { states: { all: [] } } }), refresh: vi.fn() }),
  useSubscription: vi.fn(),
}))
vi.stubGlobal('useJobCancel', () => ({ requestCancel: vi.fn(), cancellingJobId: ref(null) }))

const fakeText = { getAttribute: () => 'false', observe: () => {}, unobserve: () => {} }
const fakeYdoc = { getText: () => fakeText, getMap: () => ({ get: () => null, set: () => {} }) }

const stubs = {
  Icon: { template: '<i />' },
  Badge: {
    template: '<span class="badge"><slot /></span>',
    props: ['color', 'solid'],
  },
  Popover: { template: '<span><slot name="trigger" /><slot /></span>' },
  CommonSlugEditor: {
    template: '<div class="mock-slug" />',
    props: ['item', 'editable', 'ydoc'],
    emits: ['edit'],
  },
  AttributesInput: {
    template: '<div class="mock-attr-input" />',
    props: ['item', 'attribute', 'editable', 'toolsEnabled', 'onRunTool'],
  },
}

// The sidebar has a top-level await, so it must mount inside Suspense.
async function mountSidebar(extraProps: Record<string, unknown> = {}) {
  const Host = defineComponent({
    render() {
      return h(Suspense, null, {
        default: () => h(AttributesSidebar as never, {
          content: { __typename: 'Metadata', id: 'meta-1', slug: 'test-doc' },
          state: { state: 'published' },
          ydoc: fakeYdoc,
          attributes: new Map(),
          uploader: {},
          editable: true,
          toolsEnabled: true,
          onRunTool: () => {},
          ...extraProps,
        }),
      })
    },
  })
  const wrapper = mount(Host, { global: { stubs } })
  await new Promise((r) => setTimeout(r, 0))
  await nextTick()
  return wrapper
}

describe('AttributesSidebar', () => {
  it('renders the slug editor by default', async () => {
    const wrapper = await mountSidebar()
    expect(wrapper.find('.mock-slug').exists()).toBe(true)
  })

  it('hides the slug editor when show-slug is false', async () => {
    const wrapper = await mountSidebar({ showSlug: false })
    expect(wrapper.find('.mock-slug').exists()).toBe(false)
  })

  it('renders the slug editor read-only', async () => {
    const wrapper = await mountSidebar()
    const slug = wrapper.findComponent('.mock-slug') as VueWrapper
    expect((slug.props() as Record<string, unknown>).editable).toBe(false)
  })

  it('emits edit-slug when the slug field requests editing', async () => {
    const wrapper = await mountSidebar()
    const slug = wrapper.findComponent('.mock-slug') as VueWrapper
    slug.vm.$emit('edit')
    await nextTick()
    const sidebar = wrapper.findComponent(AttributesSidebar)
    expect(sidebar.emitted('edit-slug')).toBeTruthy()
  })

  it('renders the workflow state badge without the solid style', async () => {
    const wrapper = await mountSidebar()
    const badges = wrapper.findAllComponents('.badge') as VueWrapper[]
    const stateBadge = badges.find((b) => b.text() === 'published')
    expect(stateBadge).toBeDefined()
    expect((stateBadge!.props() as Record<string, unknown>).solid).toBeFalsy()
  })

  it('explains an empty field list when the template has no attributes', async () => {
    const wrapper = await mountSidebar()
    expect(wrapper.find('.sidebar-empty').text()).toBe('No template attributes.')
  })

  it('lets the host page customize the empty-state message', async () => {
    const wrapper = await mountSidebar({ emptyText: 'Pick a template first.' })
    expect(wrapper.find('.sidebar-empty').text()).toBe('Pick a template first.')
  })

  it('does not show the empty state when the template has attributes', async () => {
    const attributes = new Map([['title', { type: 'STRING', list: false, ui: null, textValue: '' }]])
    const wrapper = await mountSidebar({ attributes })
    expect(wrapper.find('.sidebar-empty').exists()).toBe(false)
  })
})
