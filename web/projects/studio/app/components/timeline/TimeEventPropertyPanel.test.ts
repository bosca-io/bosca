import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import TimeEventPropertyPanel from './TimeEventPropertyPanel.vue'
import type { TimeEvent, TimeEventType } from '~/composables/useTimeEvents'

const mockAttrStates = ref(new Map<string, Record<string, unknown>>())

vi.mock('~/composables/useTimeEventAttributes', () => ({
  useTimeEventAttributes: () => ({
    attributes: mockAttrStates,
    rawAttributes: ref({}),
    parentCollections: ref([]),
    relationships: ref([]),
    extractAttributes: () => ({}),
  }),
}))

vi.mock('~/composables/useUploader', () => ({
  useUploader: () => ({}),
}))

const makeType = (id = 'chapter'): TimeEventType => ({
  id,
  name: 'Chapter',
  description: '',
  schema: null,
  configuration: {},
  attributes: [],
})

const makeEvent = (): TimeEvent => ({
  id: 'evt-1',
  metadataId: 'meta-1',
  metadataVersion: 1,
  type: makeType(),
  startOffsetMs: 5000,
  endOffsetMs: 10000,
  sort: 0,
  attributes: {},
  created: '',
  modified: '',
  durationMs: 5000,
})

const stubs = {
  Modal: {
    template: '<div class="mock-modal"><slot /><slot name="footer" /></div>',
    props: ['title', 'subtitle', 'icon', 'accent', 'width'],
    emits: ['close'],
  },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  AttributesInput: { template: '<div class="mock-attr-input" />' },
  AttributesTextArea: { template: '<div class="mock-attr-textarea" />' },
  AttributesNumberInput: { template: '<div class="mock-attr-number" />' },
  AttributesDateTimeInput: { template: '<div class="mock-attr-datetime" />' },
  AttributesImage: { template: '<div class="mock-attr-image" />' },
  AttributesFile: { template: '<div class="mock-attr-file" />' },
  AttributesMetadatas: { template: '<div class="mock-attr-metadatas" />' },
  AttributesCollection: { template: '<div class="mock-attr-collection" />' },
  AttributesCollections: { template: '<div class="mock-attr-collections" />' },
}

describe('TimeEventPropertyPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAttrStates.value = new Map()
  })

  it('renders a modal titled Edit Event', () => {
    const wrapper = mount(TimeEventPropertyPanel, {
      props: { event: makeEvent(), eventTypes: [makeType()], currentTimeMs: 0 },
      global: { stubs },
    })
    const modal = wrapper.findComponent('.mock-modal') as VueWrapper
    expect(modal.exists()).toBe(true)
    expect(modal.props()).toMatchObject({ title: 'Edit Event' })
  })

  it('emits close when the modal closes or cancel is clicked', async () => {
    const wrapper = mount(TimeEventPropertyPanel, {
      props: { event: makeEvent(), eventTypes: [makeType()], currentTimeMs: 0 },
      global: { stubs },
    })
    await wrapper.find('.cancel-btn').trigger('click')
    const modal = wrapper.findComponent('.mock-modal') as VueWrapper
    modal.vm.$emit('close')
    expect(wrapper.emitted('close')).toHaveLength(2)
  })

  it('renders type selector with event types', () => {
    const types = [makeType('chapter'), { ...makeType('annotation'), name: 'Annotation' }]
    const wrapper = mount(TimeEventPropertyPanel, {
      props: { event: makeEvent(), eventTypes: types, currentTimeMs: 0 },
      global: { stubs },
    })
    const options = wrapper.findAll('.field-select option')
    expect(options.length).toBe(2)
    expect(options[0]!.text()).toBe('Chapter')
    expect(options[1]!.text()).toBe('Annotation')
  })

  it('renders start and end time inputs', () => {
    const wrapper = mount(TimeEventPropertyPanel, {
      props: { event: makeEvent(), eventTypes: [makeType()], currentTimeMs: 0 },
      global: { stubs },
    })
    const inputs = wrapper.findAll('.field-input--mono')
    expect(inputs.length).toBe(2)
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('0:05.0')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('0:10.0')
  })

  it('shows duration display', () => {
    const wrapper = mount(TimeEventPropertyPanel, {
      props: { event: makeEvent(), eventTypes: [makeType()], currentTimeMs: 0 },
      global: { stubs },
    })
    expect(wrapper.find('.duration-display').text()).toContain('Duration:')
    expect(wrapper.find('.duration-display').text()).toContain('0:05.0')
  })

  it('renders save button', () => {
    const wrapper = mount(TimeEventPropertyPanel, {
      props: { event: makeEvent(), eventTypes: [makeType()], currentTimeMs: 0 },
      global: { stubs },
    })
    expect(wrapper.find('.save-btn').exists()).toBe(true)
  })

  it('emits save with correct input on save click', async () => {
    const wrapper = mount(TimeEventPropertyPanel, {
      props: { event: makeEvent(), eventTypes: [makeType()], currentTimeMs: 0 },
      global: { stubs },
    })
    await wrapper.find('.save-btn').trigger('click')
    expect(wrapper.emitted('save')).toBeTruthy()
    const input = (wrapper.emitted('save')![0] as unknown[])[0] as Record<string, unknown>
    expect(input.type).toBe('chapter')
    expect(input.startOffsetMs).toBe(5000)
    expect(input.endOffsetMs).toBe(10000)
  })

  it('opens delete confirmation on delete button click', async () => {
    const wrapper = mount(TimeEventPropertyPanel, {
      props: { event: makeEvent(), eventTypes: [makeType()], currentTimeMs: 0 },
      global: { stubs },
    })
    await wrapper.find('.panel-delete-btn').trigger('click')
    expect(document.querySelector('.confirm-dialog')).not.toBeNull()
  })

  it('renders playhead set buttons', () => {
    const wrapper = mount(TimeEventPropertyPanel, {
      props: { event: makeEvent(), eventTypes: [makeType()], currentTimeMs: 3000 },
      global: { stubs },
    })
    const timeBtns = wrapper.findAll('.time-btn')
    expect(timeBtns.length).toBeGreaterThanOrEqual(2)
  })

  it('hides duration for point events', () => {
    const pointEvent = { ...makeEvent(), endOffsetMs: null, durationMs: null }
    const wrapper = mount(TimeEventPropertyPanel, {
      props: { event: pointEvent, eventTypes: [makeType()], currentTimeMs: 0 },
      global: { stubs },
    })
    expect(wrapper.find('.duration-display').exists()).toBe(false)
  })

  const mountWithAttr = (attr: Record<string, unknown>) => {
    mockAttrStates.value = new Map([[attr.key as string, attr]])
    return mount(TimeEventPropertyPanel, {
      props: { event: makeEvent(), eventTypes: [makeType()], currentTimeMs: 0 },
      global: { stubs },
    })
  }

  it.each([
    [{ key: 'a', ui: 'INPUT', type: 'STRING', list: false }, '.mock-attr-input'],
    [{ key: 'a', ui: 'INPUT', type: 'INT', list: false }, '.mock-attr-number'],
    [{ key: 'a', ui: 'INPUT', type: 'FLOAT', list: false }, '.mock-attr-number'],
    [{ key: 'a', ui: 'INPUT', type: 'DATE_TIME', list: false }, '.mock-attr-datetime'],
    [{ key: 'a', ui: 'TEXTAREA', type: 'STRING', list: false }, '.mock-attr-textarea'],
    [{ key: 'a', ui: 'IMAGE', type: 'METADATA', list: false }, '.mock-attr-image'],
    [{ key: 'a', ui: 'METADATA', type: 'METADATA', list: false }, '.mock-attr-file'],
    [{ key: 'a', ui: 'METADATA', type: 'METADATA', list: true }, '.mock-attr-metadatas'],
    [{ key: 'a', ui: 'FILE', type: 'METADATA', list: false }, '.mock-attr-file'],
    [{ key: 'a', ui: 'COLLECTION', type: 'COLLECTION', list: false }, '.mock-attr-collection'],
    [{ key: 'a', ui: 'COLLECTION', type: 'COLLECTION', list: true }, '.mock-attr-collections'],
  ])('renders the editor matching attribute ui/type/list (%o)', (attr, selector) => {
    const wrapper = mountWithAttr(attr)
    expect(wrapper.find(selector).exists()).toBe(true)
  })
})
