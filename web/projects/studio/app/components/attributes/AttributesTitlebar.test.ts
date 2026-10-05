import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import AttributesTitlebar from './AttributesTitlebar.vue'
import type { AttributeState } from '~/utils/editor/attribute'
import type { Metadata } from '~/types/graphql'

const stubs = {
  Icon: { template: '<i class="icon" />' },
  AttributesToolButton: { template: '<div class="tool-btn" />' },
  AttributesPublishModal: { template: '<div />' },
  Badge: { template: '<span class="badge"><slot /></span>', props: ['color'] },
  Popover: {
    template: '<span class="mock-popover"><slot name="trigger" /><slot /></span>',
    props: ['placement', 'trigger', 'interactive', 'delay'],
  },
}

function makeBaseAttribute(): AttributeState {
  return {
    name: 'Title',
    type: 'string',
    key: 'title',
    value: 'test',
    hasValue: false,
    changeRef: ref(0),
    relationships: ref([]),
    parentCollections: ref([]),
    addListener: vi.fn(),
    removeListener: vi.fn(),
  } as unknown as AttributeState
}
const noop = () => {}

describe('AttributesTitlebar', () => {
  it('renders attribute name', () => {
    const wrapper = mount(AttributesTitlebar, {
      props: { item: null, attribute: makeBaseAttribute(), editable: true, toolsEnabled: true, onRunTool: noop },
      global: { stubs },
    })
    expect(wrapper.find('.titlebar-label').text()).toBe('Title')
  })

  it('does not show status badges when showStatus is false', () => {
    const wrapper = mount(AttributesTitlebar, {
      props: {
        item: { workflow: { state: 'published' }, locked: true, public: true } as unknown as Metadata,
        attribute: makeBaseAttribute(), editable: true, toolsEnabled: true, onRunTool: noop,
      },
      global: { stubs },
    })
    expect(wrapper.find('.titlebar-badge').exists()).toBe(false)
  })

  it('shows workflow badge when showStatus is true', () => {
    const wrapper = mount(AttributesTitlebar, {
      props: {
        showStatus: true,
        item: { workflow: { state: 'published', pending: null }, locked: false, public: false, relationships: [] } as unknown as Metadata,
        attribute: makeBaseAttribute(), editable: true, toolsEnabled: true, onRunTool: noop,
      },
      global: { stubs },
    })
    expect(wrapper.find('.titlebar-badge').text()).toBe('Published')
  })

  it('shows not-public indicator when notPublic prop is set and attribute has value', () => {
    const attr = makeBaseAttribute()
    ;(attr as unknown as Record<string, unknown>).hasValue = true
    const wrapper = mount(AttributesTitlebar, {
      props: {
        showStatus: true,
        notPublic: true,
        item: { workflow: { state: 'draft' } } as unknown as Metadata,
        attribute: attr, editable: true, toolsEnabled: true, onRunTool: noop,
      },
      global: { stubs },
    })
    expect(wrapper.find('.indicator-badge--not-public').exists()).toBe(true)
  })

  it('hides not-public indicator when attribute has no value', () => {
    const wrapper = mount(AttributesTitlebar, {
      props: {
        showStatus: true,
        notPublic: true,
        item: { workflow: { state: 'draft' } } as unknown as Metadata,
        attribute: makeBaseAttribute(), editable: true, toolsEnabled: true, onRunTool: noop,
      },
      global: { stubs },
    })
    expect(wrapper.find('.indicator-badge--not-public').exists()).toBe(false)
  })

  it('hides indicators when attribute has no value', () => {
    const wrapper = mount(AttributesTitlebar, {
      props: {
        showStatus: true,
        item: { workflow: { state: 'draft' } } as unknown as Metadata,
        attribute: makeBaseAttribute(), editable: true, toolsEnabled: true, onRunTool: noop,
      },
      global: { stubs },
    })
    expect(wrapper.find('.indicator-badge').exists()).toBe(false)
  })

  it('renders tool button', () => {
    const wrapper = mount(AttributesTitlebar, {
      props: { item: null, attribute: makeBaseAttribute(), editable: true, toolsEnabled: true, onRunTool: noop },
      global: { stubs },
    })
    expect(wrapper.find('.tool-btn').exists()).toBe(true)
  })

  function makeMetadataAttribute(overrides: Record<string, unknown> = {}): AttributeState {
    return {
      ...(makeBaseAttribute() as unknown as Record<string, unknown>),
      type: 'METADATA',
      list: false,
      hasValue: true,
      metadata: { id: 'meta-1', relationship: 'video.featured' },
      configuration: { relationship: 'video.featured' },
      ...overrides,
    } as unknown as AttributeState
  }

  it('shows not-connected when the referenced metadata has no relationship row', async () => {
    const wrapper = mount(AttributesTitlebar, {
      props: { item: null, attribute: makeMetadataAttribute(), editable: true, toolsEnabled: true, onRunTool: noop },
      global: { stubs },
    })
    await nextTick()
    expect(wrapper.find('.indicator-badge--unlinked').exists()).toBe(true)
  })

  it('hides not-connected when a relationship row exists for the referenced metadata', () => {
    const attr = makeMetadataAttribute({
      relationships: ref([{ relationship: 'video.featured', metadata: { id: 'meta-1' } }]),
    })
    const wrapper = mount(AttributesTitlebar, {
      props: { item: null, attribute: attr, editable: true, toolsEnabled: true, onRunTool: noop },
      global: { stubs },
    })
    expect(wrapper.find('.indicator-badge--unlinked').exists()).toBe(false)
  })

  it('treats the content as connected even when the relationship name has drifted', () => {
    const attr = makeMetadataAttribute({
      relationships: ref([{ relationship: 'file', metadata: { id: 'meta-1' } }]),
    })
    const wrapper = mount(AttributesTitlebar, {
      props: { item: null, attribute: attr, editable: true, toolsEnabled: true, onRunTool: noop },
      global: { stubs },
    })
    expect(wrapper.find('.indicator-badge--unlinked').exists()).toBe(false)
  })

  it('shows not-connected when only some list entries have relationship rows', async () => {
    const attr = makeMetadataAttribute({
      list: true,
      metadata: null,
      metadatas: [{ id: 'meta-1' }, { id: 'meta-2' }],
      relationships: ref([{ relationship: 'video.featured', metadata: { id: 'meta-1' } }]),
    })
    const wrapper = mount(AttributesTitlebar, {
      props: { item: null, attribute: attr, editable: true, toolsEnabled: true, onRunTool: noop },
      global: { stubs },
    })
    await nextTick()
    expect(wrapper.find('.indicator-badge--unlinked').exists()).toBe(true)
  })

  function makeCollectionAttribute(overrides: Record<string, unknown> = {}): AttributeState {
    return {
      ...(makeBaseAttribute() as unknown as Record<string, unknown>),
      type: 'COLLECTION',
      list: true,
      hasValue: true,
      collections: [{ id: 'col-1', name: 'Family' }],
      collection: null,
      // A generic "Collections" picker has no configured type filter.
      configuration: null,
      parentCollections: ref([]),
      ...overrides,
    } as unknown as AttributeState
  }

  it('shows not-connected when a selected collection has no saved parent membership', async () => {
    const wrapper = mount(AttributesTitlebar, {
      props: { item: null, attribute: makeCollectionAttribute(), editable: true, toolsEnabled: true, onRunTool: noop },
      global: { stubs },
    })
    await nextTick()
    expect(wrapper.find('.indicator-badge--unlinked').exists()).toBe(true)
  })

  it('hides not-connected when each selected collection is a saved parent collection, even with no configured type', () => {
    // The parent-collection membership carries a concrete type tag that the
    // attribute's (null) configuration.type can never match — connection must
    // be determined by collection id, not by the type tag.
    const attr = makeCollectionAttribute({
      collections: [{ id: 'col-1', name: 'Family' }, { id: 'col-2', name: 'Small Group' }],
      parentCollections: ref([
        { id: 'col-1', name: 'Family', attributes: { type: 'Topics' } },
        { id: 'col-2', name: 'Tuesday Small Group', attributes: { type: 'Group Type' } },
      ]),
    })
    const wrapper = mount(AttributesTitlebar, {
      props: { item: null, attribute: attr, editable: true, toolsEnabled: true, onRunTool: noop },
      global: { stubs },
    })
    expect(wrapper.find('.indicator-badge--unlinked').exists()).toBe(false)
  })

  it('shows not-connected when only some selected collections have saved memberships', async () => {
    const attr = makeCollectionAttribute({
      collections: [{ id: 'col-1', name: 'Family' }, { id: 'col-2', name: 'Small Group' }],
      parentCollections: ref([{ id: 'col-1', name: 'Family', attributes: { type: 'Topics' } }]),
    })
    const wrapper = mount(AttributesTitlebar, {
      props: { item: null, attribute: attr, editable: true, toolsEnabled: true, onRunTool: noop },
      global: { stubs },
    })
    await nextTick()
    expect(wrapper.find('.indicator-badge--unlinked').exists()).toBe(true)
  })

  it('hides not-connected for a single (non-list) collection matched by id', () => {
    const attr = makeCollectionAttribute({
      list: false,
      collections: [],
      collection: { id: 'col-9', name: 'Featured' },
      parentCollections: ref([{ id: 'col-9', name: 'Featured', attributes: { type: 'Featured Type' } }]),
    })
    const wrapper = mount(AttributesTitlebar, {
      props: { item: null, attribute: attr, editable: true, toolsEnabled: true, onRunTool: noop },
      global: { stubs },
    })
    expect(wrapper.find('.indicator-badge--unlinked').exists()).toBe(false)
  })
})
