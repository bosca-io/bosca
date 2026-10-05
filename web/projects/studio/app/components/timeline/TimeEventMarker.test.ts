import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TimeEventMarker from './TimeEventMarker.vue'
import type { TimeEvent, TimeEventType } from '~/composables/useTimeEvents'

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

const makeType = (): TimeEventType => ({
  id: 'chapter',
  name: 'Chapter',
  description: '',
  schema: null,
  configuration: { color: '#5ec5ff' },
  attributes: [],
})

function makePointEvent(): TimeEvent {
  return {
    id: 'evt-point',
    metadataId: 'meta-1',
    metadataVersion: 1,
    type: makeType(),
    startOffsetMs: 5000,
    endOffsetMs: null,
    sort: 0,
    attributes: {},
    created: '',
    modified: '',
    durationMs: null,
  }
}

function makeRangeEvent(): TimeEvent {
  return {
    id: 'evt-range',
    metadataId: 'meta-1',
    metadataVersion: 1,
    type: makeType(),
    startOffsetMs: 5000,
    endOffsetMs: 15000,
    sort: 0,
    attributes: {},
    created: '',
    modified: '',
    durationMs: 10000,
  }
}

const stubs = {
  TimeEventPreview: { template: '<div />' },
}

describe('TimeEventMarker', () => {
  it('renders point marker for events without endOffsetMs', () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makePointEvent(), pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    expect(wrapper.find('.marker--point').exists()).toBe(true)
    expect(wrapper.find('.marker--range').exists()).toBe(false)
  })

  it('renders range bar for events with endOffsetMs', () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makeRangeEvent(), pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    expect(wrapper.find('.marker--range').exists()).toBe(true)
    expect(wrapper.find('.marker--point').exists()).toBe(false)
  })

  it('positions point marker based on startOffsetMs and pixelsPerMs', () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makePointEvent(), pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    const style = wrapper.find('.marker').attributes('style')
    expect(style).toContain('left: 500px')
  })

  it('sizes range bar based on duration', () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makeRangeEvent(), pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    const style = wrapper.find('.marker').attributes('style')
    expect(style).toContain('width: 1000px')
  })

  it('shows selected state', () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makePointEvent(), pixelsPerMs: 0.1, selected: true },
      global: { stubs },
    })
    expect(wrapper.find('.marker--selected').exists()).toBe(true)
  })

  it('emits select on mousedown', async () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makePointEvent(), pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    await wrapper.find('.marker').trigger('mousedown', { button: 0, clientX: 500 })
    expect(wrapper.emitted('select')).toBeTruthy()
  })

  it('emits open on click without drag', async () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makePointEvent(), pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    await wrapper.find('.marker').trigger('mousedown', { button: 0, clientX: 500 })
    document.dispatchEvent(new MouseEvent('mouseup'))
    expect(wrapper.emitted('open')).toBeTruthy()
  })

  it('does not emit open when the marker was dragged', async () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makePointEvent(), pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    await wrapper.find('.marker').trigger('mousedown', { button: 0, clientX: 500 })
    document.dispatchEvent(new MouseEvent('mousemove', { clientX: 700 }))
    document.dispatchEvent(new MouseEvent('mouseup'))
    expect(wrapper.emitted('open')).toBeFalsy()
    expect(wrapper.emitted('move')).toBeTruthy()
  })

  it('shows type name in range marker when wide enough', () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makeRangeEvent(), pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    expect(wrapper.find('.marker-label').text()).toBe('Chapter')
  })

  it('hides type name in range marker when too narrow', () => {
    const narrowEvent = { ...makeRangeEvent(), endOffsetMs: 5100 }
    const wrapper = mount(TimeEventMarker, {
      props: { event: narrowEvent, pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    expect(wrapper.find('.marker-label').exists()).toBe(false)
  })

  it('uses color from type configuration', () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makeRangeEvent(), pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    const style = wrapper.find('.marker').attributes('style')
    expect(style).toContain('background-color: #5ec5ff')
  })

  it('renders diamond for point events', () => {
    const wrapper = mount(TimeEventMarker, {
      props: { event: makePointEvent(), pixelsPerMs: 0.1, selected: false },
      global: { stubs },
    })
    expect(wrapper.find('.marker-diamond').exists()).toBe(true)
  })
})
