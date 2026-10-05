import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TimelineTrack from './TimelineTrack.vue'
import type { TimeEvent, TimeEventType } from '~/composables/useTimeEvents'

const makeType = (id = 'chapter'): TimeEventType => ({
  id,
  name: 'Chapter',
  description: '',
  schema: null,
  configuration: { color: '#5ec5ff' },
  attributes: [],
})

const makeEvent = (overrides: Partial<TimeEvent> = {}): TimeEvent => ({
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
  ...overrides,
})

const stubs = {
  TimeEventMarker: {
    template: '<div class="mock-marker" />',
    props: ['event', 'pixelsPerMs', 'selected'],
  },
}

describe('TimelineTrack', () => {
  const defaultProps = {
    type: makeType(),
    events: [makeEvent()],
    durationMs: 60000,
    currentTimeMs: 5000,
    pixelsPerMs: 0.1,
    selectedEventId: null as string | null,
  }

  it('renders type label with name', () => {
    const wrapper = mount(TimelineTrack, { props: defaultProps, global: { stubs } })
    expect(wrapper.find('.track-name').text()).toBe('Chapter')
  })

  it('renders color dot from type configuration', () => {
    const wrapper = mount(TimelineTrack, { props: defaultProps, global: { stubs } })
    const dot = wrapper.find('.track-dot')
    expect(dot.attributes('style')).toContain('background-color: #5ec5ff')
  })

  it('renders marker for each event', () => {
    const wrapper = mount(TimelineTrack, {
      props: { ...defaultProps, events: [makeEvent(), makeEvent({ id: 'evt-2', startOffsetMs: 15000 })] },
      global: { stubs },
    })
    expect(wrapper.findAll('.mock-marker').length).toBe(2)
  })

  it('renders playhead line at currentTimeMs', () => {
    const wrapper = mount(TimelineTrack, { props: defaultProps, global: { stubs } })
    const playhead = wrapper.find('.track-playhead')
    expect(playhead.attributes('style')).toContain('left: 500px')
  })

  it('sets track area width based on duration and zoom', () => {
    const wrapper = mount(TimelineTrack, { props: defaultProps, global: { stubs } })
    const area = wrapper.find('.track-area')
    expect(area.attributes('style')).toContain('width: 6000px')
  })

  it('renders empty track when no events', () => {
    const wrapper = mount(TimelineTrack, {
      props: { ...defaultProps, events: [] },
      global: { stubs },
    })
    expect(wrapper.findAll('.mock-marker').length).toBe(0)
    expect(wrapper.find('.track-label').exists()).toBe(true)
  })
})
