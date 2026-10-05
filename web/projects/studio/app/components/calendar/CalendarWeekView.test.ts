import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import CalendarWeekView from './CalendarWeekView.vue'
import type { MonthEvent } from '~/components/calendar/CalendarMonthView.vue'

const stubs = { Icon: { template: '<span />' } }

function makeEvent(overrides: Partial<MonthEvent> = {}): MonthEvent {
  return {
    id: 'evt-1', title: 'Meeting', allDay: false,
    startsAt: '2026-05-11T10:00:00.000Z', endsAt: '2026-05-11T11:00:00.000Z',
    source: 'USER', calendarId: 'cal-1', ...overrides,
  }
}

const defaultProps = {
  current: new Date(2026, 4, 11), // Monday May 11
  events: [] as MonthEvent[],
  calendarColors: { 'cal-1': '#3a86ff' },
}

describe('CalendarWeekView', () => {
  it('renders 7 day headers', () => {
    const wrapper = mount(CalendarWeekView, { props: defaultProps, global: { stubs } })
    const headers = wrapper.findAll('.g-header.g-day')
    expect(headers.length).toBe(7)
  })

  it('renders hour labels', () => {
    const wrapper = mount(CalendarWeekView, { props: defaultProps, global: { stubs } })
    const labels = wrapper.findAll('.hour-label')
    expect(labels.length).toBe(24)
  })

  it('renders 7 day columns', () => {
    const wrapper = mount(CalendarWeekView, { props: defaultProps, global: { stubs } })
    const cols = wrapper.findAll('.day-column')
    expect(cols.length).toBe(7)
  })

  it('renders timed events', () => {
    const wrapper = mount(CalendarWeekView, {
      props: { ...defaultProps, events: [makeEvent()] },
      global: { stubs },
    })
    const events = wrapper.findAll('.timed-event')
    expect(events.length).toBeGreaterThan(0)
  })

  it('shows event title', () => {
    const wrapper = mount(CalendarWeekView, {
      props: { ...defaultProps, events: [makeEvent({ title: 'Standup' })] },
      global: { stubs },
    })
    expect(wrapper.find('.timed-event-title').text()).toBe('Standup')
  })

  it('renders all-day events', () => {
    const wrapper = mount(CalendarWeekView, {
      props: {
        ...defaultProps,
        events: [makeEvent({ allDay: true, title: 'Holiday' })],
      },
      global: { stubs },
    })
    expect(wrapper.find('.all-day-chip').text()).toBe('Holiday')
  })

  it('emits selectSlot on hour click', async () => {
    const wrapper = mount(CalendarWeekView, { props: defaultProps, global: { stubs } })
    await wrapper.findAll('.hour-slot')[5]!.trigger('click')
    expect(wrapper.emitted('selectSlot')).toBeTruthy()
  })

  it('emits selectEvent on event click', async () => {
    const event = makeEvent()
    const wrapper = mount(CalendarWeekView, {
      props: { ...defaultProps, events: [event] },
      global: { stubs },
    })
    await wrapper.find('.timed-event').trigger('click')
    expect(wrapper.emitted('selectEvent')).toBeTruthy()
  })

  it('shows resize handle for USER events', () => {
    const wrapper = mount(CalendarWeekView, {
      props: { ...defaultProps, events: [makeEvent({ source: 'USER' })] },
      global: { stubs },
    })
    expect(wrapper.find('.resize-handle').exists()).toBe(true)
  })

  it('hides resize handle for non-USER events', () => {
    const wrapper = mount(CalendarWeekView, {
      props: { ...defaultProps, events: [makeEvent({ source: 'CAMPAIGN' })] },
      global: { stubs },
    })
    expect(wrapper.find('.resize-handle').exists()).toBe(false)
  })
})
