import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import { defineComponent } from 'vue'
import GlassTable from './GlassTable.vue'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })
const OverflowMenuStub = defineComponent({
  props: ['items'],
  emits: ['select'],
  template: '<div class="overflow-stub"><slot :toggle="() => {}" /></div>',
})

const columns = [
  { key: 'name', label: 'Name', width: '2fr' },
  { key: 'status', label: 'Status', width: '1fr' },
]

const rows = [
  { id: '1', name: 'Item A', status: 'Active' },
  { id: '2', name: 'Item B', status: 'Inactive' },
]

function mountTable(props: Record<string, unknown> = {}, slots?: Record<string, any>) {
  return shallowMount(GlassTable, {
    props: { columns, rows, ...props },
    slots,
    global: { stubs: { Icon: IconStub, OverflowMenu: OverflowMenuStub } },
  })
}

describe('GlassTable', () => {
  it('renders column headers', () => {
    const w = mountTable()
    const headers = w.findAll('.gt-header-cell')
    // one header cell per column; no trailing cell by default (arrow off)
    expect(headers.length).toBeGreaterThanOrEqual(2)
    expect(headers[0].text()).toBe('Name')
    expect(headers[1].text()).toBe('Status')
  })

  it('renders rows', () => {
    const w = mountTable()
    const rowEls = w.findAll('.gt-row')
    expect(rowEls).toHaveLength(2)
  })

  it('renders cell values from row data', () => {
    const w = mountTable()
    const cells = w.findAll('.gt-cell')
    expect(cells[0].text()).toContain('Item A')
    expect(cells[1].text()).toContain('Active')
  })

  it('emits row-click when row is clicked', async () => {
    const w = mountTable()
    await w.findAll('.gt-row')[0].trigger('click')
    expect(w.emitted('row-click')).toHaveLength(1)
    expect(w.emitted('row-click')![0]![0]).toEqual(rows[0])
  })

  it('shows loading state', () => {
    const w = mountTable({ loading: true })
    expect(w.find('.gt-state').text()).toBe('Loading…')
    expect(w.findAll('.gt-row')).toHaveLength(0)
  })

  it('shows custom loading text', () => {
    const w = mountTable({ loading: true, loadingText: 'Please wait…' })
    expect(w.find('.gt-state').text()).toBe('Please wait…')
  })

  it('shows empty state when rows is empty', () => {
    const w = mountTable({ rows: [] })
    expect(w.find('.gt-state').text()).toBe('No items.')
  })

  it('shows custom empty text', () => {
    const w = mountTable({ rows: [], emptyText: 'Nothing here.' })
    expect(w.find('.gt-state').text()).toBe('Nothing here.')
  })

  it('does not render arrow by default', () => {
    const w = mountTable()
    expect(w.find('.gt-arrow').exists()).toBe(false)
  })

  it('renders arrow when arrow is true', () => {
    const w = mountTable({ arrow: true })
    expect(w.find('.gt-arrow').exists()).toBe(true)
  })

  it('hides arrow when arrow is false and no actions', () => {
    const w = mountTable({ arrow: false })
    expect(w.find('.gt-arrow').exists()).toBe(false)
  })

  it('applies muted class to muted columns', () => {
    const w = mountTable({
      columns: [
        { key: 'name', label: 'Name' },
        { key: 'status', label: 'Status', muted: true },
      ],
    })
    const cells = w.findAll('.gt-cell')
    expect(cells[1].classes()).toContain('gt-muted')
  })

  it('applies alignment from column config to header and body cells', () => {
    const w = mountTable({
      columns: [
        { key: 'name', label: 'Name', align: 'right' },
      ],
    })
    // Header cells are flex containers, so alignment is justify-content.
    const headerCell = w.find('.gt-header-cell')
    expect(headerCell.attributes('style')).toContain('justify-content: flex-end')
    const bodyCell = w.find('.gt-cell')
    expect(bodyCell.attributes('style')).toContain('text-align: right')
  })

  it('centers header content for center-aligned columns', () => {
    const w = mountTable({
      columns: [
        { key: 'name', label: 'Name', align: 'center' },
      ],
    })
    const headerCell = w.find('.gt-header-cell')
    expect(headerCell.attributes('style')).toContain('justify-content: center')
  })

  it('leaves header justification unset for default-aligned columns', () => {
    const w = mountTable({
      columns: [
        { key: 'name', label: 'Name' },
      ],
    })
    const headerCell = w.find('.gt-header-cell')
    expect(headerCell.attributes('style') ?? '').not.toContain('justify-content')
  })
})
