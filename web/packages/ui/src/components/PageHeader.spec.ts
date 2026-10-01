import { describe, it, expect, afterEach } from 'vitest'
import { shallowMount } from '../test-helpers'
import { defineComponent, nextTick } from 'vue'
import PageHeader from './PageHeader.vue'
import OverflowMenu from './OverflowMenu.vue'

const NuxtLinkStub = defineComponent({
  props: ['to'],
  template: '<span class="nuxt-link"><slot /></span>',
})

function mountHeader(props: Record<string, unknown> = {}, slots?: Record<string, string>) {
  return shallowMount(PageHeader, {
    props: { title: 'Page Title', ...props },
    slots,
    global: {
      stubs: { NuxtLink: NuxtLinkStub },
    },
  })
}

describe('PageHeader', () => {
  it('renders title', () => {
    const w = mountHeader({ title: 'Dashboard' })
    expect(w.find('.title').text()).toBe('Dashboard')
  })

  it('renders subtitle', () => {
    const w = mountHeader({ subtitle: 'Overview' })
    expect(w.find('.subtitle').text()).toBe('Overview')
  })

  it('hides subtitle when not provided', () => {
    const w = mountHeader()
    expect(w.find('.subtitle').exists()).toBe(false)
  })

  it('renders breadcrumbs with separators', () => {
    const w = mountHeader({ breadcrumb: ['Home', 'Settings', 'General'] })
    const seps = w.findAll('.breadcrumb-sep')
    expect(seps).toHaveLength(2)
    expect(w.find('.breadcrumb').text()).toContain('Home')
    expect(w.find('.breadcrumb').text()).toContain('Settings')
    expect(w.find('.breadcrumb').text()).toContain('General')
  })

  it('renders NuxtLink for breadcrumbs with to', () => {
    const w = mountHeader({
      breadcrumb: [{ label: 'Home', to: '/' }, 'Current'],
    })
    const links = w.findAllComponents(NuxtLinkStub)
    expect(links).toHaveLength(1)
    expect(links[0].props('to')).toBe('/')
    expect(links[0].text()).toBe('Home')
  })

  it('renders tabs', () => {
    const w = mountHeader({ tabs: ['Tab A', 'Tab B', 'Tab C'] })
    const tabs = w.findAll('button.tab')
    expect(tabs).toHaveLength(3)
    expect(tabs[0].text()).toBe('Tab A')
  })

  it('applies active tab styling', () => {
    const w = mountHeader({ tabs: ['Tab A', 'Tab B'], activeTab: 'Tab B' })
    const tabs = w.findAll('button.tab')
    const activeStyle = tabs[1].attributes('style')
    expect(activeStyle).toContain('font-weight: 500')
  })

  it('emits tab on click', async () => {
    const w = mountHeader({ tabs: ['Tab A', 'Tab B'] })
    await w.findAll('button.tab')[1].trigger('click')
    expect(w.emitted('tab')).toHaveLength(1)
    expect(w.emitted('tab')![0]).toEqual(['Tab B'])
  })

  it('renders actions slot', () => {
    const w = mountHeader({}, { actions: '<button>Action</button>' })
    expect(w.find('.actions').text()).toContain('Action')
  })

  it('renders the #subtitle slot in its own row below the title', () => {
    const w = mountHeader({}, { subtitle: '<span class="badge">Ready</span> meta-text' })
    const row = w.find('.subtitle-row')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('meta-text')
    expect(row.find('.badge').exists()).toBe(true)
  })

  it('prefers the #subtitle slot over the inline subtitle prop', () => {
    const w = mountHeader(
      { subtitle: 'Inline subtitle' },
      { subtitle: '<span>Rich subtitle</span>' },
    )
    expect(w.find('.subtitle-row').exists()).toBe(true)
    // inline subtitle should not render when the slot is used
    expect(w.find('.subtitle').exists()).toBe(false)
  })

  it('does not render the subtitle row when only the prop is provided', () => {
    const w = mountHeader({ subtitle: 'Just text' })
    expect(w.find('.subtitle-row').exists()).toBe(false)
    expect(w.find('.subtitle').text()).toBe('Just text')
  })
})

describe('PageHeader tab overflow', () => {
  // happy-dom reports zero layout sizes; stub widths so every tab (and the
  // More trigger) measures `itemWidth` and the .tabs container `containerWidth`.
  function stubSizes(containerWidth: number, itemWidth: number): () => void {
    const offset = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth')
    const client = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth')
    Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
      configurable: true,
      get() { return itemWidth },
    })
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
      configurable: true,
      get(this: Element) { return this.classList.contains('tabs') ? containerWidth : itemWidth },
    })
    return () => {
      if (offset) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', offset)
      if (client) Object.defineProperty(HTMLElement.prototype, 'clientWidth', client)
    }
  }

  let restore: (() => void) | undefined

  afterEach(() => {
    restore?.()
    restore = undefined
  })

  it('shows all tabs when they fit', async () => {
    restore = stubSizes(1000, 80)
    const w = mountHeader({ tabs: ['A', 'B', 'C', 'D', 'E'] })
    await nextTick()
    expect(w.findAll('button.tab')).toHaveLength(5)
    expect(w.findComponent(OverflowMenu).exists()).toBe(false)
  })

  it('collapses tabs that do not fit into a More menu', async () => {
    // 5 tabs x 80px + gaps = 416px in a 300px container -> 2 visible + More
    restore = stubSizes(300, 80)
    const w = mountHeader({ tabs: ['A', 'B', 'C', 'D', 'E'] })
    await nextTick()
    const visible = w.findAll('button.tab')
    expect(visible.map(t => t.text())).toEqual(['A', 'B'])
    const menu = w.findComponent(OverflowMenu)
    expect(menu.exists()).toBe(true)
    expect(menu.props('items')).toEqual([
      { id: 'C', label: 'C' },
      { id: 'D', label: 'D' },
      { id: 'E', label: 'E' },
    ])
  })

  it('keeps the active tab visible by promoting it out of the overflow', async () => {
    restore = stubSizes(300, 80)
    const w = mountHeader({ tabs: ['A', 'B', 'C', 'D', 'E'], activeTab: 'E' })
    await nextTick()
    const visible = w.findAll('button.tab')
    expect(visible.map(t => t.text())).toEqual(['A', 'E'])
    expect(w.findComponent(OverflowMenu).props('items')).toEqual([
      { id: 'B', label: 'B' },
      { id: 'C', label: 'C' },
      { id: 'D', label: 'D' },
    ])
  })

  it('emits tab when selecting from the More menu', async () => {
    restore = stubSizes(300, 80)
    const w = mountHeader({ tabs: ['A', 'B', 'C', 'D', 'E'] })
    await nextTick()
    w.findComponent(OverflowMenu).vm.$emit('select', 'D')
    expect(w.emitted('tab')).toEqual([['D']])
  })

  it('recomputes when the tabs change', async () => {
    restore = stubSizes(300, 80)
    const w = mountHeader({ tabs: ['A', 'B', 'C', 'D', 'E'] })
    await nextTick()
    expect(w.findAll('button.tab')).toHaveLength(2)
    await w.setProps({ tabs: ['A', 'B'] })
    await nextTick()
    await nextTick()
    expect(w.findAll('button.tab')).toHaveLength(2)
    expect(w.findComponent(OverflowMenu).exists()).toBe(false)
  })
})
