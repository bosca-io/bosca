import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import CalendarDayView from './CalendarDayView.vue'
import type { MonthEvent } from '~/components/calendar/CalendarMonthView.vue'

function makeEvent(overrides: Partial<MonthEvent> = {}): MonthEvent {
  return {
    id: 'evt-1', title: 'Meeting', allDay: false,
    startsAt: '2026-05-15T10:00:00.000Z', endsAt: '2026-05-15T11:00:00.000Z',
    source: 'USER', calendarId: 'cal-1', ...overrides,
  }
}

describe('CalendarDayView', () => {
  const defaultProps = {
    current: new Date(2026, 4, 15),
    events: [] as MonthEvent[],
    calendarColors: { 'cal-1': '#3a86ff' },
  }

  it('renders single day column', () => {
    const wrapper = mount(CalendarDayView, { props: defaultProps })
    expect(wrapper.findAll('.day-column').length).toBe(1)
  })

  it('renders 24 hour labels', () => {
    const wrapper = mount(CalendarDayView, { props: defaultProps })
    expect(wrapper.findAll('.hour-label').length).toBe(24)
  })

  it('renders timed events', () => {
    const wrapper = mount(CalendarDayView, {
      props: { ...defaultProps, events: [makeEvent()] },
    })
    expect(wrapper.findAll('.timed-event').length).toBe(1)
  })

  it('shows all-day section when all-day events exist', () => {
    const wrapper = mount(CalendarDayView, {
      props: { ...defaultProps, events: [makeEvent({ allDay: true })] },
    })
    expect(wrapper.find('.all-day-section').exists()).toBe(true)
  })

  it('hides all-day section when no all-day events', () => {
    const wrapper = mount(CalendarDayView, {
      props: { ...defaultProps, events: [makeEvent({ allDay: false })] },
    })
    expect(wrapper.find('.all-day-section').exists()).toBe(false)
  })

  it('emits selectSlot on hour click', async () => {
    const wrapper = mount(CalendarDayView, { props: defaultProps })
    await wrapper.findAll('.hour-slot')[10]!.trigger('click')
    expect(wrapper.emitted('selectSlot')).toBeTruthy()
  })

  it('emits selectEvent on event click', async () => {
    const wrapper = mount(CalendarDayView, {
      props: { ...defaultProps, events: [makeEvent()] },
    })
    await wrapper.find('.timed-event').trigger('click')
    expect(wrapper.emitted('selectEvent')).toBeTruthy()
  })

  it('shows resize handle for USER events', () => {
    const wrapper = mount(CalendarDayView, {
      props: { ...defaultProps, events: [makeEvent({ source: 'USER' })] },
    })
    expect(wrapper.find('.resize-handle').exists()).toBe(true)
  })
})
