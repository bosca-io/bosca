import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import { defineComponent } from 'vue'
import ConfirmModal from './ConfirmModal.vue'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })
const ButtonStub = defineComponent({ template: '<button><slot /></button>' })

function mountConfirm(props: Record<string, unknown> = {}, slots?: Record<string, string>) {
  return shallowMount(ConfirmModal, {
    props: { title: 'Delete item?', ...props },
    slots,
    global: {
      stubs: { Icon: IconStub, Button: ButtonStub, Teleport: true },
    },
  })
}

describe('ConfirmModal', () => {
  it('renders title', () => {
    const w = mountConfirm({ title: 'Remove entry?' })
    expect(w.find('.confirm-title').text()).toBe('Remove entry?')
  })

  it('renders default subtitle', () => {
    const w = mountConfirm()
    expect(w.find('.confirm-subtitle').text()).toBe('This cannot be undone.')
  })

  it('renders custom subtitle', () => {
    const w = mountConfirm({ subtitle: 'Are you sure?' })
    expect(w.find('.confirm-subtitle').text()).toBe('Are you sure?')
  })

  it('renders custom confirmLabel', () => {
    const w = mountConfirm({ confirmLabel: 'Remove' })
    expect(w.find('.delete-btn').text()).toBe('Remove')
  })

  it('renders default confirmLabel', () => {
    const w = mountConfirm()
    expect(w.find('.delete-btn').text()).toBe('Delete')
  })

  it('emits close on backdrop click', async () => {
    const w = mountConfirm()
    await w.find('.modal-backdrop').trigger('click')
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('emits confirm on delete button click', async () => {
    const w = mountConfirm()
    await w.find('.delete-btn').trigger('click')
    expect(w.emitted('confirm')).toHaveLength(1)
  })

  it('shows Deleting… and disables button when loading', () => {
    const w = mountConfirm({ loading: true })
    const btn = w.find('.delete-btn')
    expect(btn.text()).toBe('Deleting…')
    expect((btn.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('renders body slot', () => {
    const w = mountConfirm({}, { default: '<p>Extra info</p>' })
    expect(w.find('.confirm-body').text()).toContain('Extra info')
  })
})
