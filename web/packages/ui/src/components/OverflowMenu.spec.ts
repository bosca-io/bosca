import { describe, it, expect } from 'vitest'
import { mount } from '../test-helpers'
import { defineComponent, nextTick } from 'vue'
import OverflowMenu from './OverflowMenu.vue'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })

const items = [
  { id: 'edit', label: 'Edit', icon: 'pencil' },
  { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
]

function mountMenu(props: Record<string, unknown> = {}, slots?: Record<string, any>) {
  return mount(OverflowMenu, {
    props: { items, ...props },
    slots: {
      default: '<button class="trigger-btn">Menu</button>',
      ...slots,
    },
    global: { stubs: { Icon: IconStub } },
  })
}

describe('OverflowMenu', () => {
  it('renders trigger slot', () => {
    const w = mountMenu()
    expect(w.find('.trigger-btn').exists()).toBe(true)
  })

  it('menu is hidden by default', () => {
    const w = mountMenu()
    expect(w.find('.overflow-menu').exists()).toBe(false)
  })

  it('opens menu when open model is true', async () => {
    const w = mountMenu({ open: true })
    await nextTick()
    expect(w.find('.overflow-menu').exists()).toBe(true)
  })

  it('renders menu items when open', async () => {
    const w = mountMenu({ open: true })
    await nextTick()
    const buttons = w.findAll('.overflow-item')
    expect(buttons).toHaveLength(2)
    expect(buttons[0].text()).toBe('Edit')
    expect(buttons[1].text()).toBe('Delete')
  })

  it('emits select on item click', async () => {
    const w = mountMenu({ open: true })
    await nextTick()
    await w.findAll('.overflow-item')[0].trigger('click')
    expect(w.emitted('select')).toHaveLength(1)
    expect(w.emitted('select')![0]).toEqual(['edit'])
  })

  it('closes menu after item selection', async () => {
    const w = mountMenu({ open: true })
    await nextTick()
    await w.findAll('.overflow-item')[0].trigger('click')
    await nextTick()
    expect(w.find('.overflow-menu').exists()).toBe(false)
  })

  it('applies danger class to danger items', async () => {
    const w = mountMenu({ open: true })
    await nextTick()
    const dangerItem = w.findAll('.overflow-item')[1]
    expect(dangerItem.classes()).toContain('danger')
  })

  it('applies disabled class to disabled items', async () => {
    const w = mountMenu({
      open: true,
      items: [{ id: 'disabled-item', label: 'Disabled', disabled: true }],
    })
    await nextTick()
    const item = w.find('.overflow-item')
    expect(item.classes()).toContain('disabled')
    expect((item.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('does not emit select for disabled items', async () => {
    const w = mountMenu({
      open: true,
      items: [{ id: 'disabled-item', label: 'Disabled', disabled: true }],
    })
    await nextTick()
    await w.find('.overflow-item').trigger('click')
    expect(w.emitted('select')).toBeUndefined()
  })

  it('renders separator dividers', async () => {
    const w = mountMenu({
      open: true,
      items: [
        { id: 'a', label: 'A' },
        { id: 'sep', label: '', separator: true },
        { id: 'b', label: 'B' },
      ],
    })
    await nextTick()
    expect(w.find('.overflow-sep').exists()).toBe(true)
  })

  it('applies anchor-right class by default', async () => {
    const w = mountMenu({ open: true })
    await nextTick()
    expect(w.find('.overflow-menu').classes()).toContain('anchor-right')
  })

  it('applies anchor-left class when specified', async () => {
    const w = mountMenu({ open: true, anchor: 'left' })
    await nextTick()
    expect(w.find('.overflow-menu').classes()).toContain('anchor-left')
  })

  it('cleans up listeners on unmount', async () => {
    const w = mountMenu({ open: true })
    await nextTick()
    w.unmount()
  })
})
