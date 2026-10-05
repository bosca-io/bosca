import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import AuthBrandPanel from './AuthBrandPanel.vue'

// Mutable state backing the mocked useAppOverrides composable.
const overrideState = {
  expandedLogo: ref<string | null>(null),
  title: ref<string | null>(null),
  hideTitle: ref(false),
}

vi.stubGlobal('useAppOverrides', () => ({
  expandedLogo: overrideState.expandedLogo,
  title: overrideState.title,
  hideTitle: overrideState.hideTitle,
  hasOverrides: ref(
    Boolean(overrideState.expandedLogo.value || overrideState.title.value),
  ),
}))

const stubs = {
  BoscaMark: { template: '<span class="bosca-mark" />', props: ['size', 'color'] },
}

function mountPanel() {
  return mount(AuthBrandPanel, { global: { stubs } })
}

beforeEach(() => {
  overrideState.expandedLogo.value = null
  overrideState.title.value = null
  overrideState.hideTitle.value = false
})

describe('AuthBrandPanel', () => {
  it('always renders the Bosca | Studio mark', () => {
    const wrapper = mountPanel()
    expect(wrapper.find('.bosca-mark').exists()).toBe(true)
    expect(wrapper.text()).toContain('Bosca')
    expect(wrapper.text()).toContain('Studio')
  })

  it('does not render any override branding when there are no overrides', () => {
    const wrapper = mountPanel()
    expect(wrapper.find('.brand-override-logo').exists()).toBe(false)
    expect(wrapper.find('.brand-override-title').exists()).toBe(false)
  })

  it('renders the override logo and title next to the Bosca mark', () => {
    overrideState.expandedLogo.value = '/content/image/acme-logo'
    overrideState.title.value = 'Acme'

    const wrapper = mountPanel()
    const brandTop = wrapper.find('.brand-top')
    const logo = brandTop.find('.brand-override-logo')
    expect(logo.exists()).toBe(true)
    expect(logo.attributes('src')).toBe('/content/image/acme-logo')
    expect(logo.attributes('alt')).toBe('Acme')
    expect(brandTop.find('.brand-override-title').text()).toBe('Acme')
  })

  it('renders the title alone when no override logo is configured', () => {
    overrideState.title.value = 'Acme'

    const wrapper = mountPanel()
    expect(wrapper.find('.brand-override-logo').exists()).toBe(false)
    expect(wrapper.find('.brand-override-title').text()).toBe('Acme')
  })

  it('hides the title when hideTitle is set, keeping the logo', () => {
    overrideState.expandedLogo.value = '/content/image/acme-logo'
    overrideState.title.value = 'Acme'
    overrideState.hideTitle.value = true

    const wrapper = mountPanel()
    expect(wrapper.find('.brand-override-logo').exists()).toBe(true)
    expect(wrapper.find('.brand-override-title').exists()).toBe(false)
  })

  it('falls back to a generic alt when a logo has no title', () => {
    overrideState.expandedLogo.value = '/content/image/acme-logo'

    const wrapper = mountPanel()
    expect(wrapper.find('.brand-override-logo').attributes('alt')).toBe('Logo')
  })
})
