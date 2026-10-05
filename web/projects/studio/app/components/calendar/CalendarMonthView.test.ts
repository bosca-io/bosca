import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import CalendarMonthView from './CalendarMonthView.vue'
import type { MonthEvent } from './CalendarMonthView.vue'

const stubs = {
  Icon: { template: '<span />', props: ['name', 'size'] },
}

function makeEvent(overrides: Partial<MonthEvent> = {}): MonthEvent {
  return {
    id: 'evt-1',
    title: 'Test Event',
    allDay: false,
    startsAt: '2026-05-15T10:00:00.000Z',
    endsAt: '2026-05-15T11:00:00.000Z',
    source: 'USER',
    calendarId: 'cal-1',
    ...overrides,
  }
}

describe('CalendarMonthView', () => {
  const defaultProps = {
    current: new Date(2026, 4, 1), // May 2026
    events: [] as MonthEvent[],
    calendarColors: { 'cal-1': '#3a86ff' } as Record<string, string>,
  }

  it('renders weekday headers', () => {
    const wrapper = mount(CalendarMonthView, { props: defaultProps, global: { stubs } })
    const labels = wrapper.findAll('.weekday-label')
    expect(labels.length).toBe(7)
    expect(labels[0]!.text()).toBe('Mon')
    expect(labels[6]!.text()).toBe('Sun')
  })

  it('renders 42 day cells (6 weeks × 7 days)', () => {
    const wrapper = mount(CalendarMonthView, { props: defaultProps, global: { stubs } })
    const cells = wrapper.findAll('.day-cell')
    expect(cells.length).toBe(42)
  })

  it('marks today with special class', () => {
    const todayProps = {
      ...defaultProps,
      current: new Date(),
    }
    const wrapper = mount(CalendarMonthView, { props: todayProps, global: { stubs } })
    const todayCells = wrapper.findAll('.day-cell--today')
    expect(todayCells.length).toBe(1)
  })

  it('dims days outside current month', () => {
    const wrapper = mount(CalendarMonthView, { props: defaultProps, global: { stubs } })
    const otherCells = wrapper.findAll('.day-cell--other')
    expect(otherCells.length).toBeGreaterThan(0)
  })

  it('renders events in day cells', () => {
    const wrapper = mount(CalendarMonthView, {
      props: {
        ...defaultProps,
        events: [makeEvent()],
      },
      global: { stubs },
    })
    const chips = wrapper.findAll('.event-chip')
    expect(chips.length).toBeGreaterThan(0)
  })

  it('shows event title', () => {
    const wrapper = mount(CalendarMonthView, {
      props: {
        ...defaultProps,
        events: [makeEvent({ title: 'My Meeting' })],
      },
      global: { stubs },
    })
    const chip = wrapper.find('.event-chip')
    expect(chip.find('.event-title').text()).toBe('My Meeting')
  })

  it('shows overflow indicator for more than 3 events', () => {
    const events = Array.from({ length: 5 }, (_, i) =>
      makeEvent({ id: `evt-${i}`, title: `Event ${i}` }),
    )
    const wrapper = mount(CalendarMonthView, {
      props: { ...defaultProps, events },
      global: { stubs },
    })
    const overflow = wrapper.find('.event-overflow')
    expect(overflow.exists()).toBe(true)
    expect(overflow.text()).toContain('+2 more')
  })

  it('emits selectDay on day cell click', async () => {
    const wrapper = mount(CalendarMonthView, { props: defaultProps, global: { stubs } })
    await wrapper.findAll('.day-cell')[10]!.trigger('click')
    expect(wrapper.emitted('selectDay')).toBeTruthy()
  })

  it('emits selectEvent on event click', async () => {
    const event = makeEvent()
    const wrapper = mount(CalendarMonthView, {
      props: { ...defaultProps, events: [event] },
      global: { stubs },
    })
    await wrapper.find('.event-chip').trigger('click')
    expect(wrapper.emitted('selectEvent')).toBeTruthy()
    expect((wrapper.emitted('selectEvent')![0] as unknown[])[0] as { id: string }).toHaveProperty('id', 'evt-1')
  })

  it('applies calendar color to events', () => {
    const wrapper = mount(CalendarMonthView, {
      props: {
        ...defaultProps,
        events: [makeEvent()],
      },
      global: { stubs },
    })
    const chip = wrapper.find('.event-chip')
    const style = chip.attributes('style')
    expect(style).toContain('#3a86ff')
  })

  it('shows time for non all-day events', () => {
    const wrapper = mount(CalendarMonthView, {
      props: {
        ...defaultProps,
        events: [makeEvent({ allDay: false })],
      },
      global: { stubs },
    })
    expect(wrapper.find('.event-time').exists()).toBe(true)
  })

  it('hides time for all-day events', () => {
    const wrapper = mount(CalendarMonthView, {
      props: {
        ...defaultProps,
        events: [makeEvent({ allDay: true })],
      },
      global: { stubs },
    })
    expect(wrapper.find('.event-time').exists()).toBe(false)
  })
})
