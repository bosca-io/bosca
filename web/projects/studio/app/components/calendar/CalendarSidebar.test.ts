import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import CalendarSidebar from './CalendarSidebar.vue'
import type { CalendarItem } from './CalendarSidebar.vue'

const stubs = {
  Icon: { template: '<span />', props: ['name', 'size'] },
}

const makeCal = (key: string, name: string, color = '#3a86ff'): CalendarItem => ({
  key,
  metadataId: key,
  version: 1,
  name,
  color,
  canEdit: true,
})

describe('CalendarSidebar', () => {
  it('renders calendar list', () => {
    const wrapper = mount(CalendarSidebar, {
      props: {
        calendars: [makeCal('cal-1', 'Work'), makeCal('cal-2', 'Personal')],
        visible: new Set(['cal-1', 'cal-2']),
      },
      global: { stubs },
    })
    const items = wrapper.findAll('.cal-item')
    expect(items.length).toBe(2)
    expect(items[0]!.find('.cal-name').text()).toBe('Work')
    expect(items[1]!.find('.cal-name').text()).toBe('Personal')
  })

  it('shows empty state when no calendars', () => {
    const wrapper = mount(CalendarSidebar, {
      props: { calendars: [], visible: new Set<string>() },
      global: { stubs },
    })
    expect(wrapper.find('.sidebar-empty').text()).toBe('No calendars yet.')
  })

  it('shows checkbox filled when calendar is visible', () => {
    const wrapper = mount(CalendarSidebar, {
      props: {
        calendars: [makeCal('cal-1', 'Work', '#ff0000')],
        visible: new Set(['cal-1']),
      },
      global: { stubs },
    })
    const checkbox = wrapper.find('.cal-checkbox')
    expect(checkbox.attributes('style')).toContain('background-color: #ff0000')
  })

  it('shows checkbox empty when calendar is hidden', () => {
    const wrapper = mount(CalendarSidebar, {
      props: {
        calendars: [makeCal('cal-1', 'Work', '#ff0000')],
        visible: new Set<string>(),
      },
      global: { stubs },
    })
    const checkbox = wrapper.find('.cal-checkbox')
    expect(checkbox.attributes('style')).toContain('background-color: transparent')
  })

  it('emits toggle on calendar click', async () => {
    const wrapper = mount(CalendarSidebar, {
      props: {
        calendars: [makeCal('cal-1', 'Work')],
        visible: new Set(['cal-1']),
      },
      global: { stubs },
    })
    await wrapper.find('.cal-item').trigger('click')
    expect(wrapper.emitted('toggle')).toEqual([['cal-1']])
  })

  it('emits add on plus button click', async () => {
    const wrapper = mount(CalendarSidebar, {
      props: { calendars: [], visible: new Set<string>() },
      global: { stubs },
    })
    await wrapper.find('.sidebar-add').trigger('click')
    expect(wrapper.emitted('add')).toBeTruthy()
  })

  it('emits edit on edit button click', async () => {
    const cal = makeCal('cal-1', 'Work')
    const wrapper = mount(CalendarSidebar, {
      props: { calendars: [cal], visible: new Set(['cal-1']) },
      global: { stubs },
    })
    await wrapper.find('.cal-edit').trigger('click')
    expect(wrapper.emitted('edit')).toEqual([[cal]])
  })

  it('mutes name text when calendar is hidden', () => {
    const wrapper = mount(CalendarSidebar, {
      props: {
        calendars: [makeCal('cal-1', 'Work')],
        visible: new Set<string>(),
      },
      global: { stubs },
    })
    expect(wrapper.find('.cal-name--muted').exists()).toBe(true)
  })
})
