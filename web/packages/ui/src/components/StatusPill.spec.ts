import { describe, it, expect } from 'vitest'
import { mount } from '../test-helpers'
import StatusPill from './StatusPill.vue'
import Badge from './Badge.vue'

describe('StatusPill', () => {
  const knownStatuses: Record<string, [string, string]> = {
    open: ['Open', '#5ec5ff'],
    inprog: ['In Progress', '#a78bff'],
    review: ['Review', '#ffb547'],
    blocked: ['Blocked', '#ff5d6c'],
    done: ['Done', '#34d99a'],
  }

  for (const [key, [label, color]] of Object.entries(knownStatuses)) {
    it(`renders "${label}" with color ${color} for status="${key}"`, () => {
      const w = mount(StatusPill, { props: { status: key } })
      const badge = w.findComponent(Badge)
      expect(badge.exists()).toBe(true)
      expect(badge.props('color')).toBe(color)
      expect(w.text()).toBe(label)
    })
  }

  it('renders raw status value for unknown statuses', () => {
    const w = mount(StatusPill, { props: { status: 'archived' } })
    expect(w.text()).toBe('archived')
  })

  it('uses fallback color for unknown statuses', () => {
    const w = mount(StatusPill, { props: { status: 'archived' } })
    const badge = w.findComponent(Badge)
    expect(badge.props('color')).toBe('var(--fg-3)')
  })
})
