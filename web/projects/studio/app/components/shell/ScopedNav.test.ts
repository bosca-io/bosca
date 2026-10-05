import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ScopedNav from './ScopedNav.vue'

const stubs = {
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
  CustomBrand: { template: '<div />' },
}

function mountNav() {
  return mount(ScopedNav, {
    props: { subsystem: 'cms', active: 'collections', accent: '#00dc82' },
    global: { stubs },
  })
}

describe('ScopedNav', () => {
  it('renders the omni-search bar between the subsystem nav button and the nav list', () => {
    const wrapper = mountNav()
    const search = wrapper.find('.scoped-nav-search')
    expect(search.exists()).toBe(true)
    expect(search.text()).toContain('Search…')
    expect(search.text()).toContain('⌘K')
    expect(search.element.previousElementSibling?.classList.contains('scoped-nav-header')).toBe(true)
    expect(search.element.nextElementSibling?.classList.contains('scoped-nav-groups')).toBe(true)
  })

  it('emits search-click when the search bar is clicked', async () => {
    const wrapper = mountNav()
    await wrapper.find('.scoped-nav-search').trigger('click')
    expect(wrapper.emitted('search-click')).toHaveLength(1)
  })

  it('emits brand-click when the subsystem nav button is clicked', async () => {
    const wrapper = mountNav()
    await wrapper.find('.scoped-nav-header').trigger('click')
    expect(wrapper.emitted('brand-click')).toHaveLength(1)
  })

  it('emits home when the Bosca Studio wordmark is clicked', async () => {
    const wrapper = mountNav()
    await wrapper.find('.scoped-nav-brand').trigger('click')
    expect(wrapper.emitted('home')).toHaveLength(1)
    // The wordmark must not double as the subsystem switcher.
    expect(wrapper.emitted('brand-click')).toBeUndefined()
  })

  it('emits activate with the nav item id when a nav row is clicked', async () => {
    const wrapper = mountNav()
    const first = wrapper.findAll('.scoped-nav-item')[0]
    await first?.trigger('click')
    expect(wrapper.emitted('activate')?.[0]).toEqual(['collections'])
  })
})
