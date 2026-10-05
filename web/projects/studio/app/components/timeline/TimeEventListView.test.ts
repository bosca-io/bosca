import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import TimeEventListView from './TimeEventListView.vue'
import type { TimeEvent, TimeEventType, TypeGroup } from '~/composables/useTimeEvents'

vi.mock('~/composables/useEventPreview', () => ({
  useEventHoverPreview: () => ({
    hoverEvent: ref(null),
    hoverPreview: ref(null),
    mouseX: ref(0),
    mouseY: ref(0),
    onEnter: vi.fn(),
    onMove: vi.fn(),
    onLeave: vi.fn(),
    dismiss: vi.fn(),
  }),
}))

const mockToast = {
  success: vi.fn(),
  error: vi.fn(),
  warn: vi.fn(),
  info: vi.fn(),
  add: vi.fn(),
}
vi.stubGlobal('useToast', () => mockToast)

const makeType = (id = 'chapter', attrKeys: string[] = [], attrUi = 'INPUT'): TimeEventType => ({
  id,
  name: 'Chapter',
  description: '',
  schema: null,
  configuration: {},
  attributes: attrKeys.map((k) => ({
    key: k,
    name: k.charAt(0).toUpperCase() + k.slice(1),
    description: '',
    type: 'STRING',
    ui: attrUi,
    list: false,
    configuration: null,
    supplementaryKey: null,
  })),
})

const makeEvent = (id = 'evt-1', startMs = 5000): TimeEvent => ({
  id,
  metadataId: 'meta-1',
  metadataVersion: 1,
  type: makeType(),
  startOffsetMs: startMs,
  endOffsetMs: startMs + 5000,
  sort: 0,
  attributes: {},
  created: '',
  modified: '',
  durationMs: 5000,
})

const makeGroup = (events: TimeEvent[] = [makeEvent()]): TypeGroup => ({
  type: makeType(),
  events,
})

const stubs = {
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  Badge: { template: '<span class="badge"><slot /></span>' },
  TimeEventPreview: { template: '<div />' },
  TimelineMetadataField: {
    template: '<div class="mock-metadata-field" />',
    props: ['modelValue', 'readOnly'],
  },
}

describe('TimeEventListView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders table with events', () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup()],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    const rows = wrapper.findAll('.list-row')
    expect(rows.length).toBe(1)
  })

  it('shows empty state when no events', () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup([])],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    expect(wrapper.find('.list-empty').text()).toContain('No events')
  })

  it('renders multiple events sorted by start time', () => {
    const events = [makeEvent('evt-2', 15000), makeEvent('evt-1', 5000)]
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup(events)],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    const rows = wrapper.findAll('.list-row')
    expect(rows.length).toBe(2)
  })

  it('renders attribute columns from type definitions', () => {
    const type = makeType('chapter', ['title', 'description'])
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [{ type, events: [] }],
        eventTypes: [type],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    const headers = wrapper.findAll('.list-th')
    const headerTexts = headers.map((h) => h.text())
    expect(headerTexts).toContain('Title')
    expect(headerTexts).toContain('Description')
  })

  it('highlights selected row', () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup()],
        eventTypes: [makeType()],
        selectedEventId: 'evt-1',
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    expect(wrapper.find('.list-row--selected').exists()).toBe(true)
  })

  it('emits select on row click', async () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup()],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    await wrapper.find('.list-row').trigger('click')
    expect(wrapper.emitted('select')).toBeTruthy()
    expect(wrapper.emitted('select')![0]).toEqual(['evt-1'])
  })

  it('emits seek on play button click', async () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup()],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    await wrapper.find('.seek-btn').trigger('click')
    expect(wrapper.emitted('seek')).toBeTruthy()
    expect(wrapper.emitted('seek')![0]).toEqual([5000])
  })

  it('shows type badge for each event', () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup()],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    expect(wrapper.find('.badge').text()).toBe('Chapter')
  })

  it('renders inline time inputs', () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup()],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    const timeInputs = wrapper.findAll('.inline-time')
    expect(timeInputs.length).toBe(2)
  })

  it('shows import progress when importing', () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup()],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
        csvImportProgress: { status: 'updating-events', totalRows: 10, currentRow: 5 },
      },
      global: { stubs },
    })
    expect(wrapper.find('.import-progress').exists()).toBe(true)
    expect(wrapper.find('.progress-pct').text()).toBe('50%')
  })

  it('renders select-all checkbox', () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup()],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    const checkboxes = wrapper.findAll('input[type="checkbox"]')
    expect(checkboxes.length).toBeGreaterThanOrEqual(2)
  })

  it('marks header checkbox indeterminate when only some rows are selected', () => {
    const events = [makeEvent('evt-1', 5000), makeEvent('evt-2', 15000)]
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup(events)],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
        rowSelection: { 'evt-1': true },
      },
      global: { stubs },
    })
    const header = wrapper.find('.list-th--check input[type="checkbox"]')
    expect((header.element as HTMLInputElement).indeterminate).toBe(true)
    expect((header.element as HTMLInputElement).checked).toBe(false)
  })

  it('renders metadata field for METADATA-ui attribute values', () => {
    const type = makeType('chapter', ['image'], 'METADATA')
    const event = { ...makeEvent(), type, attributes: { image: 'meta-uuid-1' } }
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [{ type, events: [event] }],
        eventTypes: [type],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    expect(wrapper.find('.mock-metadata-field').exists()).toBe(true)
  })

  it('renders metadata field for object-valued METADATA attributes', () => {
    const type = makeType('chapter', ['image'], 'METADATA')
    const event = {
      ...makeEvent(),
      type,
      attributes: { image: { id: 'meta-uuid-1', attributes: { crop: {} } } },
    }
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [{ type, events: [event] }],
        eventTypes: [type],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    expect(wrapper.find('.mock-metadata-field').exists()).toBe(true)
  })

  it('renders plain text for non-metadata attribute values', () => {
    const type = makeType('chapter', ['title'])
    const event = { ...makeEvent(), type, attributes: { title: 'Intro' } }
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [{ type, events: [event] }],
        eventTypes: [type],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    expect(wrapper.find('.mock-metadata-field').exists()).toBe(false)
    expect(wrapper.find('.list-td--attr').text()).toBe('Intro')
  })

  it('warns via toast when an unsupported file is dropped', async () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup()],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    const file = new File(['x'], 'notes.txt', { type: 'text/plain' })
    await wrapper.find('.list-container').trigger('drop', {
      dataTransfer: { files: [file] },
    })
    expect(wrapper.emitted('pdf-drop')).toBeFalsy()
    expect(wrapper.emitted('csv-drop')).toBeFalsy()
    expect(mockToast.add).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Unsupported file type', color: 'warning' }),
    )
  })

  it('emits csv-drop for CSV files', async () => {
    const wrapper = mount(TimeEventListView, {
      props: {
        typeGroups: [makeGroup()],
        eventTypes: [makeType()],
        selectedEventId: null,
        currentTimeMs: 0,
      },
      global: { stubs },
    })
    const file = new File(['a,b'], 'events.csv', { type: 'text/csv' })
    await wrapper.find('.list-container').trigger('drop', {
      dataTransfer: { files: [file] },
    })
    expect(wrapper.emitted('csv-drop')).toBeTruthy()
    expect(mockToast.add).not.toHaveBeenCalled()
  })
})
