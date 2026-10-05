import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest'
import { mount } from '../test-helpers'
import { defineComponent, nextTick } from 'vue'
import Select from './Select.vue'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })

beforeAll(() => {
  // Stub document.fonts which is not available in happy-dom
  Object.defineProperty(document, 'fonts', {
    value: { ready: Promise.resolve() },
    configurable: true,
  })
})

const options = [
  { value: 'a', label: 'Alpha' },
  { value: 'b', label: 'Beta' },
  { value: 'c', label: 'Charlie' },
]

function mountSelect(props: Record<string, unknown> = {}) {
  return mount(Select, {
    props: { options, ...props },
    global: { stubs: { Icon: IconStub, Teleport: true } },
    attachTo: document.body,
  })
}

describe('Select', () => {
  it('renders with placeholder', () => {
    const w = mountSelect()
    expect(w.find('.select-display').text()).toBe('Select…')
    w.unmount()
  })

  it('renders custom placeholder', () => {
    const w = mountSelect({ placeholder: 'Pick one' })
    expect(w.find('.select-display').text()).toBe('Pick one')
    w.unmount()
  })

  it('renders label when provided', () => {
    const w = mountSelect({ label: 'Category' })
    expect(w.find('.select-label').text()).toBe('Category')
    w.unmount()
  })

  it('shows selected option label', () => {
    const w = mountSelect({ modelValue: 'b' })
    expect(w.find('.select-display').text()).toBe('Beta')
    w.unmount()
  })

  it('opens dropdown on trigger click', async () => {
    const w = mountSelect()
    await w.find('.select-trigger').trigger('click')
    expect(w.find('.select-dropdown').exists()).toBe(true)
    w.unmount()
  })

  it('renders options in dropdown', async () => {
    const w = mountSelect()
    await w.find('.select-trigger').trigger('click')
    const opts = w.findAll('.select-option')
    expect(opts).toHaveLength(3)
    expect(opts[0].text()).toContain('Alpha')
    expect(opts[1].text()).toContain('Beta')
    expect(opts[2].text()).toContain('Charlie')
    w.unmount()
  })

  it('selects option and closes dropdown', async () => {
    const w = mountSelect({ modelValue: undefined })
    await w.find('.select-trigger').trigger('click')
    await w.findAll('.select-option')[1].trigger('click')
    await nextTick()
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1]).toEqual(['b'])
    expect(w.find('.select-dropdown').exists()).toBe(false)
    w.unmount()
  })

  it('applies disabled state', () => {
    const w = mountSelect({ disabled: true })
    expect(w.find('.select-root').classes()).toContain('disabled')
    w.unmount()
  })

  it('does not open when disabled', async () => {
    const w = mountSelect({ disabled: true })
    await w.find('.select-trigger').trigger('click')
    expect(w.find('.select-dropdown').exists()).toBe(false)
    w.unmount()
  })

  it('shows search input when searchable and open', async () => {
    const w = mountSelect({ searchable: true })
    await w.find('.select-trigger').trigger('click')
    expect(w.find('.select-search').exists()).toBe(true)
    w.unmount()
  })

  it('filters options by search query', async () => {
    const w = mountSelect({ searchable: true })
    await w.find('.select-trigger').trigger('click')
    await w.find('.select-search').setValue('alp')
    await nextTick()
    const opts = w.findAll('.select-option')
    expect(opts).toHaveLength(1)
    expect(opts[0].text()).toContain('Alpha')
    w.unmount()
  })

  it('shows empty state when no matches', async () => {
    const w = mountSelect({ searchable: true })
    await w.find('.select-trigger').trigger('click')
    await w.find('.select-search').setValue('zzzzz')
    await nextTick()
    expect(w.find('.select-empty').exists()).toBe(true)
    expect(w.find('.select-empty').text()).toBe('No matches')
    w.unmount()
  })

  it('accepts a custom value when searchable and allowCustom are enabled', async () => {
    const w = mountSelect({ searchable: true, allowCustom: true, modelValue: undefined })
    await w.find('.select-trigger').trigger('click')
    await w.find('.select-search').setValue('en-GB')
    await nextTick()

    const option = w.find('.select-option')
    expect(option.text()).toContain('Use “en-GB”')
    await option.trigger('click')

    expect(w.emitted('update:modelValue')?.at(-1)).toEqual(['en-GB'])
    await w.setProps({ modelValue: 'en-GB' })
    expect(w.find('.select-display').text()).toBe('en-GB')
    w.unmount()
  })

  it('keyboard ArrowDown highlights next option', async () => {
    const w = mountSelect({ searchable: true })
    await w.find('.select-trigger').trigger('click')
    const input = w.find('.select-search')
    await input.trigger('keydown', { key: 'ArrowDown' })
    await nextTick()
    const highlighted = w.find('[data-highlighted="true"]')
    expect(highlighted.exists()).toBe(true)
    w.unmount()
  })

  it('keyboard Enter selects highlighted option', async () => {
    const w = mountSelect({ searchable: true, modelValue: undefined })
    await w.find('.select-trigger').trigger('click')
    const input = w.find('.select-search')
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter' })
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1]).toEqual(['a'])
    w.unmount()
  })

  it('keyboard Escape closes dropdown', async () => {
    const w = mountSelect({ searchable: true })
    await w.find('.select-trigger').trigger('click')
    expect(w.find('.select-dropdown').exists()).toBe(true)
    await w.find('.select-search').trigger('keydown', { key: 'Escape' })
    await nextTick()
    expect(w.find('.select-dropdown').exists()).toBe(false)
    w.unmount()
  })

  it('multi-select renders tags', () => {
    const w = mountSelect({ multiple: true, modelValue: ['a', 'b'] })
    const tags = w.findAll('.select-tag')
    expect(tags.length).toBeGreaterThanOrEqual(2)
    w.unmount()
  })

  it('multi-select toggles selection', async () => {
    const w = mountSelect({ multiple: true, modelValue: ['a'] })
    await w.find('.select-trigger').trigger('click')
    // Click Beta to add
    await w.findAll('.select-option')[1].trigger('click')
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const last = emitted![emitted!.length - 1]![0] as string[]
    expect(last).toContain('a')
    expect(last).toContain('b')
    w.unmount()
  })

  it('multi-select remove tag button works', async () => {
    const w = mountSelect({ multiple: true, modelValue: ['a', 'b'] })
    const removeButtons = w.findAll('.select-tag-remove')
    await removeButtons[0].trigger('click')
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const last = emitted![emitted!.length - 1]![0] as string[]
    expect(last).not.toContain('a')
    expect(last).toContain('b')
    w.unmount()
  })

  it('shows overflow tag count when exceeding maxTags', () => {
    const manyOptions = [
      { value: '1', label: 'One' },
      { value: '2', label: 'Two' },
      { value: '3', label: 'Three' },
      { value: '4', label: 'Four' },
    ]
    const w = mountSelect({
      options: manyOptions,
      multiple: true,
      modelValue: ['1', '2', '3', '4'],
      maxTags: 2,
    })
    expect(w.find('.select-tag-overflow').text()).toContain('+2')
    w.unmount()
  })

  it('does not select disabled options', async () => {
    const w = mountSelect({
      options: [
        { value: 'a', label: 'Alpha' },
        { value: 'b', label: 'Beta', disabled: true },
      ],
      modelValue: undefined,
    })
    await w.find('.select-trigger').trigger('click')
    await w.findAll('.select-option')[1].trigger('click')
    expect(w.emitted('update:modelValue')).toBeUndefined()
    w.unmount()
  })

  it('applies size-sm class', () => {
    const w = mountSelect({ size: 'sm' })
    expect(w.find('.select-root').classes()).toContain('size-sm')
    w.unmount()
  })

  describe('async search (onSearch)', () => {
    const flush = () => new Promise(r => setTimeout(r, 0))

    it('loads options from onSearch when opened', async () => {
      const onSearch = vi.fn().mockResolvedValue([{ value: 'p1', label: 'Alice' }])
      const w = mountSelect({ options: undefined, searchable: true, onSearch })
      await w.find('.select-trigger').trigger('click')
      await flush()
      await nextTick()
      expect(onSearch).toHaveBeenCalledWith('')
      const opts = w.findAll('.select-option')
      expect(opts).toHaveLength(1)
      expect(opts[0].text()).toContain('Alice')
      w.unmount()
    })

    it('keeps showing a picked option label after search results change', async () => {
      // First open returns Alice; every later search returns a set that no
      // longer contains her. The trigger label must survive the result swap.
      const onSearch = vi.fn()
        .mockResolvedValueOnce([{ value: 'p1', label: 'Alice' }])
        .mockResolvedValue([{ value: 'p2', label: 'Bob' }])
      const w = mountSelect({ options: undefined, searchable: true, onSearch })
      await w.find('.select-trigger').trigger('click')
      await flush()
      await nextTick()
      await w.findAll('.select-option')[0].trigger('click')
      await w.setProps({ modelValue: 'p1' })
      expect(w.find('.select-display').text()).toBe('Alice')

      await w.find('.select-trigger').trigger('click')
      await flush()
      await nextTick()
      expect(w.find('.select-search').attributes('placeholder')).toBe('Alice')
      await w.find('.select-search').trigger('keydown', { key: 'Escape' })
      await nextTick()
      expect(w.find('.select-display').text()).toBe('Alice')
      w.unmount()
    })

    it('keeps tag labels for picked options after async results change (multiple)', async () => {
      const onSearch = vi.fn()
        .mockResolvedValueOnce([{ value: 'p1', label: 'Alice' }])
        .mockResolvedValue([{ value: 'p2', label: 'Bob' }])
      const w = mountSelect({
        options: undefined, searchable: true, multiple: true, onSearch, debounce: 0, modelValue: [],
      })
      await w.find('.select-trigger').trigger('click')
      await flush()
      await nextTick()
      await w.findAll('.select-option')[0].trigger('click')
      await w.setProps({ modelValue: ['p1'] })

      await w.find('.select-search').setValue('bo')
      await flush()
      await flush()
      await nextTick()
      expect(w.find('.select-tag-label').text()).toBe('Alice')
      w.unmount()
    })
  })
})
