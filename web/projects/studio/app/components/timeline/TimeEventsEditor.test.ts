import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import TimeEventsEditor from './TimeEventsEditor.vue'
import type { Metadata } from '~/types/graphql'

const mockAddEvent = vi.fn()
const mockEditEvent = vi.fn()
const mockDeleteEvent = vi.fn()
const mockDeleteEvents = vi.fn().mockResolvedValue(0)
const mockEditMetadataRelationshipAttributes = vi.fn()
const mockRefresh = vi.fn()
const mockEvents = ref<unknown[]>([])
const mockSelectedEvent = ref<unknown>(null)
const mockSelectedEventId = ref<string | null>(null)

vi.mock('~/composables/useTimeEvents', () => ({
  useTimeEvents: () => ({
    eventTypes: ref([
      { id: 'chapter', name: 'Chapter', description: '', schema: null, configuration: {}, attributes: [] },
      { id: 'slide', name: 'Slide', description: '', schema: null, configuration: {}, attributes: [] },
    ]),
    events: mockEvents,
    visibleTypeGroups: ref([
      { type: { id: 'chapter', name: 'Chapter', description: '', schema: null, configuration: {}, attributes: [] }, events: [] },
    ]),
    selectedEvent: mockSelectedEvent,
    selectedEventId: mockSelectedEventId,
    activeTypeFilter: ref(null),
    currentTimeMs: ref(5000),
    durationMs: ref(60000),
    addEvent: mockAddEvent,
    editEvent: mockEditEvent,
    deleteEvent: mockDeleteEvent,
    deleteEvents: mockDeleteEvents,
    editMetadataRelationshipAttributes: mockEditMetadataRelationshipAttributes,
    refresh: mockRefresh,
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

vi.mock('~/composables/usePdfTimelineImport', () => ({
  usePdfTimelineImport: () => ({
    progress: ref({ status: 'idle' }),
    isImporting: ref(false),
    importPdf: vi.fn(),
  }),
}))

vi.mock('~/composables/useCsvTimelineImport', () => ({
  useCsvTimelineImport: () => ({
    progress: ref({ status: 'idle', totalRows: 0, currentRow: 0 }),
    isImporting: ref(false),
    importCsv: vi.fn(),
  }),
}))

const mockUseGraphQL = {
  query: vi.fn(),
  mutation: vi.fn(),
  useAsyncQuery: () => ({ data: ref(null), refresh: vi.fn() }),
  useSubscription: vi.fn(),
}

vi.stubGlobal('useGraphQL', () => mockUseGraphQL)

vi.mock('plyr', () => ({ default: function MockPlyr() {} }))
vi.mock('hls.js', () => ({ default: Object.assign(function MockHls() {}, { isSupported: () => true, Events: { MANIFEST_PARSED: '' } }) }))
vi.mock('plyr/dist/plyr.css', () => ({}))

const stubs = {
  ClientOnly: { template: '<slot />' },
  SMediaPlayer: { template: '<div class="mock-player" />', props: ['item'] },
  TimelineRuler: { template: '<div class="mock-ruler" />' },
  TimelineTrack: { template: '<div class="mock-track" />', props: ['type', 'events', 'durationMs', 'currentTimeMs', 'pixelsPerMs', 'selectedEventId'] },
  TimeEventPropertyPanel: {
    template: '<div class="mock-panel" />',
    props: ['event', 'eventTypes', 'currentTimeMs'],
    emits: ['save', 'delete', 'close'],
  },
  TimeEventListView: { template: '<div class="mock-list-view" />' },
  Icon: { template: '<span />', props: ['name', 'size'] },
}

const makeMetadata = () => ({
  __typename: 'Metadata' as const,
  id: 'meta-1',
  name: 'test video',
  version: 1,
  content: { type: 'video/mp4' },
  attributes: {},
  media: null,
})

const makeSelectedEvent = (id = 'evt-1') => ({
  id,
  metadataId: 'meta-1',
  metadataVersion: 1,
  type: { id: 'chapter', name: 'Chapter', description: '', schema: null, configuration: {}, attributes: [] },
  startOffsetMs: 0,
  endOffsetMs: null,
  sort: 0,
  attributes: {},
  created: '',
  modified: '',
  durationMs: null,
})

describe('TimeEventsEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockEvents.value = []
    mockSelectedEvent.value = null
    mockSelectedEventId.value = null
  })

  it('renders media player', () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    expect(wrapper.find('.mock-player').exists()).toBe(true)
  })

  it('shows timeline view by default', () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    expect(wrapper.find('.mock-ruler').exists()).toBe(true)
    expect(wrapper.find('.mock-list-view').exists()).toBe(false)
  })

  it('toggles to list view', async () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })

    const listBtn = wrapper.findAll('.toolbar-btn').find((b) => b.attributes('title') === 'List view')
    expect(listBtn).toBeDefined()
    await listBtn!.trigger('click')

    expect(wrapper.find('.mock-list-view').exists()).toBe(true)
    expect(wrapper.find('.mock-ruler').exists()).toBe(false)
  })

  it('renders type filter tabs', () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    const tabs = wrapper.findAll('.toolbar-tab')
    expect(tabs.length).toBe(3) // All + Chapter + Slide
    expect(tabs[0]!.text()).toBe('All')
    expect(tabs[1]!.text()).toBe('Chapter')
    expect(tabs[2]!.text()).toBe('Slide')
  })

  it('renders add event button', () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    const addBtn = wrapper.find('.toolbar-btn--primary')
    expect(addBtn.exists()).toBe(true)
    expect(addBtn.text()).toContain('Add Event')
  })

  it('calls addEvent on add button click', async () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    await wrapper.find('.toolbar-btn--primary').trigger('click')
    expect(mockAddEvent).toHaveBeenCalledWith({
      type: 'chapter',
      startOffsetMs: 5000,
      sort: 0,
    })
  })

  it('renders time display', () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    expect(wrapper.find('.time-display').exists()).toBe(true)
    expect(wrapper.find('.time-current').text()).toBe('0:05.0')
    expect(wrapper.find('.time-duration').text()).toBe('1:00.0')
  })

  it('renders zoom controls in timeline mode', () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    const zoomIn = wrapper.findAll('.toolbar-btn').find((b) => b.attributes('title') === 'Zoom in')
    const zoomOut = wrapper.findAll('.toolbar-btn').find((b) => b.attributes('title') === 'Zoom out')
    expect(zoomIn).toBeDefined()
    expect(zoomOut).toBeDefined()
  })

  it('renders tracks for each visible type group', () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    const tracks = wrapper.findAll('.mock-track')
    expect(tracks.length).toBe(1) // one visible type group
  })

  it('hides bulk delete button when no rows selected', () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    expect(wrapper.find('.toolbar-btn--danger').exists()).toBe(false)
  })

  it('opens the event editor modal when a list row is selected', async () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    const listBtn = wrapper.findAll('.toolbar-btn').find((b) => b.attributes('title') === 'List view')
    await listBtn!.trigger('click')
    expect(wrapper.find('.mock-panel').exists()).toBe(false)

    mockSelectedEvent.value = makeSelectedEvent()
    const listView = wrapper.findComponent('.mock-list-view') as VueWrapper
    listView.vm.$emit('select', 'evt-1')
    await nextTick()

    expect(mockSelectedEventId.value).toBe('evt-1')
    expect(wrapper.find('.mock-panel').exists()).toBe(true)
  })

  it('opens the event editor modal when a timeline marker is clicked open', async () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    mockSelectedEvent.value = makeSelectedEvent()
    const track = wrapper.findComponent('.mock-track') as VueWrapper
    track.vm.$emit('open', 'evt-1')
    await nextTick()

    expect(wrapper.find('.mock-panel').exists()).toBe(true)
  })

  it('closes the event editor modal on close', async () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    mockSelectedEvent.value = makeSelectedEvent()
    const track = wrapper.findComponent('.mock-track') as VueWrapper
    track.vm.$emit('open', 'evt-1')
    await nextTick()

    const panel = wrapper.findComponent('.mock-panel') as VueWrapper
    panel.vm.$emit('close')
    await nextTick()

    expect(wrapper.find('.mock-panel').exists()).toBe(false)
    expect(mockSelectedEventId.value).toBe(null)
  })

  it('saves and closes the modal when the editor emits save', async () => {
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    mockSelectedEvent.value = makeSelectedEvent()
    const track = wrapper.findComponent('.mock-track') as VueWrapper
    track.vm.$emit('open', 'evt-1')
    await nextTick()

    const input = { type: 'chapter', startOffsetMs: 100, endOffsetMs: null, sort: 0, attributes: {} }
    const panel = wrapper.findComponent('.mock-panel') as VueWrapper
    panel.vm.$emit('save', input)
    await flushPromises()

    expect(mockEditEvent).toHaveBeenCalledWith('evt-1', input)
    expect(wrapper.find('.mock-panel').exists()).toBe(false)
  })

  it('syncs crop data to metadata relationships after saving an event', async () => {
    mockEvents.value = [
      {
        id: 'evt-1',
        metadataId: 'meta-1',
        metadataVersion: 1,
        type: {
          id: 'chapter',
          name: 'Chapter',
          description: '',
          schema: null,
          configuration: {},
          attributes: [
            {
              key: 'image',
              name: 'Image',
              description: '',
              type: 'METADATA',
              ui: 'METADATA',
              list: false,
              configuration: { relationship: 'image.featured' },
              supplementaryKey: null,
            },
          ],
        },
        startOffsetMs: 0,
        endOffsetMs: null,
        sort: 0,
        attributes: {},
        created: '',
        modified: '',
        durationMs: null,
      },
    ]
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    const listBtn = wrapper.findAll('.toolbar-btn').find((b) => b.attributes('title') === 'List view')
    await listBtn!.trigger('click')

    const input = {
      type: 'chapter',
      startOffsetMs: 0,
      endOffsetMs: null,
      sort: 0,
      attributes: { image: { id: 'meta-9', attributes: { crop: { x: 1 } } } },
    }
    const listView = wrapper.findComponent('.mock-list-view') as VueWrapper
    listView.vm.$emit('save', 'evt-1', input)
    await flushPromises()

    expect(mockEditEvent).toHaveBeenCalledWith('evt-1', input)
    expect(mockEditMetadataRelationshipAttributes).toHaveBeenCalledWith(
      'evt-1',
      'meta-9',
      'image.featured',
      { crop: { x: 1 } },
    )
    mockEvents.value = []
  })

  it('does not sync relationships when attribute has no relationship configuration', async () => {
    mockEvents.value = [
      {
        id: 'evt-1',
        metadataId: 'meta-1',
        metadataVersion: 1,
        type: {
          id: 'chapter',
          name: 'Chapter',
          description: '',
          schema: null,
          configuration: {},
          attributes: [
            {
              key: 'image',
              name: 'Image',
              description: '',
              type: 'METADATA',
              ui: 'METADATA',
              list: false,
              configuration: {},
              supplementaryKey: null,
            },
          ],
        },
        startOffsetMs: 0,
        endOffsetMs: null,
        sort: 0,
        attributes: {},
        created: '',
        modified: '',
        durationMs: null,
      },
    ]
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    const listBtn = wrapper.findAll('.toolbar-btn').find((b) => b.attributes('title') === 'List view')
    await listBtn!.trigger('click')

    const listView = wrapper.findComponent('.mock-list-view') as VueWrapper
    listView.vm.$emit('save', 'evt-1', {
      type: 'chapter',
      startOffsetMs: 0,
      endOffsetMs: null,
      sort: 0,
      attributes: { image: { id: 'meta-9', attributes: { crop: { x: 1 } } } },
    })
    await flushPromises()

    expect(mockEditMetadataRelationshipAttributes).not.toHaveBeenCalled()
    mockEvents.value = []
  })

  it('reports skipped events when bulk delete removes fewer than selected', async () => {
    mockDeleteEvents.mockResolvedValue(1)
    const wrapper = mount(TimeEventsEditor, {
      props: { metadata: makeMetadata() as unknown as Metadata },
      global: { stubs },
    })
    const listBtn = wrapper.findAll('.toolbar-btn').find((b) => b.attributes('title') === 'List view')
    await listBtn!.trigger('click')

    const listView = wrapper.findComponent('.mock-list-view') as VueWrapper
    listView.vm.$emit('update:rowSelection', { 'evt-1': true, 'evt-2': true })
    await nextTick()

    await wrapper.find('.toolbar-btn--danger').trigger('click')
    await nextTick()

    const deleteBtn = document.querySelector('.confirm-btn--delete') as HTMLButtonElement
    expect(deleteBtn).not.toBeNull()
    deleteBtn.click()
    await flushPromises()

    expect(mockDeleteEvents).toHaveBeenCalledWith(['evt-1', 'evt-2'])
    expect(mockToast.add).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Deleted 1 event, 1 skipped', color: 'warning' }),
    )
    mockDeleteEvents.mockResolvedValue(0)
  })
})
