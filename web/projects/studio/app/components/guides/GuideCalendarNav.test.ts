import { describe, it, expect } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import GuideCalendarNav from './GuideCalendarNav.vue'
import { localDayKey } from '~/utils/guideCalendar'

const stubs = {
  Popover: {
    template: '<div class="popover"><slot name="trigger" /><div class="popover-body"><slot /></div></div>',
    props: ['modelValue', 'placement', 'trigger', 'interactive', 'delay'],
    emits: ['update:modelValue'],
  },
  Icon: {
    template: '<i :data-icon="name" />',
    props: ['name', 'size', 'color'],
  },
}

function mountNav(props: { steps: Array<{ date?: string | null }>, currentPage: number }) {
  return mount(GuideCalendarNav, { props, global: { stubs } })
}

/** The in-month day button showing `day`. */
function dayButton(wrapper: VueWrapper, day: number) {
  const button = wrapper
    .findAll('.guide-cal-day')
    .find(b => !b.classes('outside') && b.text() === String(day))
  expect(button, `day button ${day}`).toBeDefined()
  return button!
}

/** Today's local calendar day shifted by whole days, as an ISO step date. */
function localDayOffsetIso(offsetDays: number): string {
  const now = new Date()
  const shifted = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offsetDays)
  return `${localDayKey(shifted)}T00:00:00Z`
}

const marchSteps = [
  { date: '2026-03-02T00:00:00Z' },
  { date: '2026-03-03T00:00:00Z' },
  { date: '2026-03-06T00:00:00Z' },
]

describe('GuideCalendarNav', () => {
  it('renders nothing when no step has a date', () => {
    const wrapper = mountNav({ steps: [{ date: null }, {}], currentPage: 1 })
    expect(wrapper.find('.guide-cal-trigger').exists()).toBe(false)
  })

  it('opens on the active step month and marks its day selected', () => {
    const wrapper = mountNav({ steps: marchSteps, currentPage: 3 })
    expect(wrapper.find('.guide-cal-month').text()).toBe('March 2026')
    expect(wrapper.find('.guide-cal-day.selected').text()).toBe('3')
  })

  it('enables only the days that have steps', () => {
    const wrapper = mountNav({ steps: marchSteps, currentPage: 2 })
    const enabled = wrapper
      .findAll('.guide-cal-day')
      .filter(b => (b.element as HTMLButtonElement).disabled === false)
    expect(enabled.map(b => b.text())).toEqual(['2', '3', '6'])
  })

  it('emits the step page for a clicked day and closes the popover', async () => {
    const wrapper = mountNav({ steps: marchSteps, currentPage: 2 })
    const popover = wrapper.findComponent(stubs.Popover)
    popover.vm.$emit('update:modelValue', true)
    await nextTick()

    await dayButton(wrapper, 6).trigger('click')

    expect(wrapper.emitted('select')).toEqual([[4]])
    expect(popover.props('modelValue')).toBe(false)
  })

  it('clamps month navigation to the months that have steps', () => {
    const wrapper = mountNav({ steps: marchSteps, currentPage: 2 })
    const [prev, next] = wrapper.findAll('.guide-cal-nav')
    expect((prev!.element as HTMLButtonElement).disabled).toBe(true)
    expect((next!.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('moves between months within the guide range and snaps back on reopen', async () => {
    const steps = [
      { date: '2026-03-31T00:00:00Z' },
      { date: '2026-04-01T00:00:00Z' },
    ]
    const wrapper = mountNav({ steps, currentPage: 2 })
    const [prev, next] = wrapper.findAll('.guide-cal-nav')
    expect((prev!.element as HTMLButtonElement).disabled).toBe(true)

    await next!.trigger('click')
    expect(wrapper.find('.guide-cal-month').text()).toBe('April 2026')
    expect((next!.element as HTMLButtonElement).disabled).toBe(true)

    // Reopening recenters on the active step's month.
    const popover = wrapper.findComponent(stubs.Popover)
    popover.vm.$emit('update:modelValue', false)
    await nextTick()
    popover.vm.$emit('update:modelValue', true)
    await nextTick()
    expect(wrapper.find('.guide-cal-month').text()).toBe('March 2026')
  })

  it('rings today and jumps to its step from the Today shortcut', async () => {
    const steps = [
      { date: localDayOffsetIso(-1) },
      { date: localDayOffsetIso(0) },
      { date: localDayOffsetIso(1) },
    ]
    const wrapper = mountNav({ steps, currentPage: 3 })

    const todayDay = Number(localDayKey().slice(-2))
    expect(dayButton(wrapper, todayDay).classes('today')).toBe(true)

    const today = wrapper.find('.guide-cal-today')
    expect((today.element as HTMLButtonElement).disabled).toBe(false)
    await today.trigger('click')
    expect(wrapper.emitted('select')).toEqual([[3]])
  })

  it('disables the Today shortcut when today has no step', () => {
    const wrapper = mountNav({ steps: marchSteps, currentPage: 2 })
    expect((wrapper.find('.guide-cal-today').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('shows the nearest step month when opened from the introduction', () => {
    const wrapper = mountNav({
      steps: [{ date: '2020-06-10T00:00:00Z' }, { date: '2020-06-12T00:00:00Z' }],
      currentPage: 1,
    })
    expect(wrapper.find('.guide-cal-month').text()).toBe('June 2020')
  })
})
