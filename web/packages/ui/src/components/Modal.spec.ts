import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import { defineComponent } from 'vue'
import Modal from './Modal.vue'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })

function mountModal(props: Record<string, unknown> = {}, slots?: Record<string, string>) {
  return shallowMount(Modal, {
    props: { title: 'Test Modal', ...props },
    slots,
    global: {
      stubs: { Icon: IconStub, Teleport: true },
    },
  })
}

describe('Modal', () => {
  it('renders title', () => {
    const w = mountModal({ title: 'My Title' })
    expect(w.find('.modal-title').text()).toBe('My Title')
  })

  it('renders subtitle when provided', () => {
    const w = mountModal({ subtitle: 'Some subtitle' })
    expect(w.find('.modal-subtitle').text()).toBe('Some subtitle')
  })

  it('hides subtitle when not provided', () => {
    const w = mountModal()
    expect(w.find('.modal-subtitle').exists()).toBe(false)
  })

  it('renders icon when provided', () => {
    const w = mountModal({ icon: 'gear' })
    expect(w.find('.modal-icon').exists()).toBe(true)
    expect(w.findComponent(IconStub).props('name')).toBe('gear')
  })

  it('hides icon when not provided', () => {
    const w = mountModal()
    expect(w.find('.modal-icon').exists()).toBe(false)
  })

  it('emits close on backdrop click', async () => {
    const w = mountModal()
    await w.find('.modal-backdrop').trigger('click')
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('emits close on close button click', async () => {
    const w = mountModal()
    await w.find('.modal-close').trigger('click')
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('renders body slot', () => {
    const w = mountModal({}, { default: '<p>Body content</p>' })
    expect(w.find('.modal-body').text()).toContain('Body content')
  })

  it('renders footer slot when provided', () => {
    const w = mountModal({}, { footer: '<button>Save</button>' })
    expect(w.find('.modal-footer').exists()).toBe(true)
    expect(w.find('.modal-footer').text()).toContain('Save')
  })

  it('hides footer when no slot', () => {
    const w = mountModal()
    expect(w.find('.modal-footer').exists()).toBe(false)
  })
})
