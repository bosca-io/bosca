import { describe, it, expect, vi } from 'vitest'
import { shallowMount } from '../test-helpers'
import tippy from 'tippy.js'
import Popover from './Popover.vue'

vi.mock('tippy.js', () => ({
  default: vi.fn(() => ({ show: vi.fn(), hide: vi.fn(), destroy: vi.fn() })),
}))

function mountPopover(props: Record<string, unknown> = {}, slots?: Record<string, string>) {
  return shallowMount(Popover, {
    props,
    slots: {
      trigger: '<button>Open</button>',
      default: '<div>Content</div>',
      ...slots,
    },
  })
}

describe('Popover', () => {
  it('renders trigger slot', () => {
    const w = mountPopover()
    expect(w.find('.popover-trigger').text()).toContain('Open')
  })

  it('renders content slot', () => {
    const w = mountPopover()
    expect(w.find('.popover-content').text()).toContain('Content')
  })

  it('has trigger and content refs as elements', () => {
    const w = mountPopover()
    expect(w.find('.popover-trigger').exists()).toBe(true)
    expect(w.find('.popover-content').exists()).toBe(true)
  })

  it('renders with default props', () => {
    const w = mountPopover()
    expect(w.exists()).toBe(true)
  })

  it('passes a zero show delay to tippy by default', () => {
    mountPopover()
    const options = vi.mocked(tippy).mock.calls.at(-1)?.[1] as { delay: [number, number] }
    expect(options.delay).toEqual([0, 0])
  })

  it('passes the configured show delay to tippy', () => {
    mountPopover({ delay: 250 })
    const options = vi.mocked(tippy).mock.calls.at(-1)?.[1] as { delay: [number, number] }
    expect(options.delay).toEqual([250, 0])
  })
})
