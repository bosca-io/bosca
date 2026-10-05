import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import Drawer from './Drawer.vue'

function cleanup() {
  document.body.innerHTML = ''
}

describe('Drawer', () => {
  afterEach(cleanup)

  it('teleports the panel to document.body', () => {
    const wrapper = mount(Drawer, {
      props: { title: 'Pod details' },
      slots: { default: '<p>body content</p>' },
      attachTo: document.body,
    })
    const panel = document.body.querySelector('.drawer-panel')
    expect(panel).not.toBeNull()
    expect(document.body.textContent).toContain('Pod details')
    expect(document.body.textContent).toContain('body content')
    wrapper.unmount()
  })

  it('emits close on backdrop click', async () => {
    const wrapper = mount(Drawer, {
      props: { title: 't' },
      attachTo: document.body,
    })
    const backdrop = document.querySelector('.drawer-backdrop') as HTMLElement
    backdrop.click()
    expect(wrapper.emitted('close')).toBeTruthy()
    wrapper.unmount()
  })

  it('does not emit close when clicking inside the panel', async () => {
    const wrapper = mount(Drawer, {
      props: { title: 't' },
      attachTo: document.body,
    })
    const panel = document.querySelector('.drawer-panel') as HTMLElement
    panel.click()
    expect(wrapper.emitted('close')).toBeFalsy()
    wrapper.unmount()
  })

  it('emits close on Escape', async () => {
    const wrapper = mount(Drawer, {
      props: { title: 't' },
      attachTo: document.body,
    })
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toBeTruthy()
    wrapper.unmount()
  })

  it('renders footer slot when provided', () => {
    const wrapper = mount(Drawer, {
      props: { title: 't' },
      slots: { footer: '<button>OK</button>' },
      attachTo: document.body,
    })
    expect(document.body.querySelector('.drawer-footer button')?.textContent).toBe('OK')
    wrapper.unmount()
  })

  it('removes its keydown listener on unmount', () => {
    const wrapper = mount(Drawer, {
      props: { title: 't' },
      attachTo: document.body,
    })
    wrapper.unmount()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toBeFalsy()
  })
})
