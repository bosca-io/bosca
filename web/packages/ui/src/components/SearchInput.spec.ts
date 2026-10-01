import { describe, it, expect } from 'vitest'
import { mount } from '../test-helpers'
import SearchInput from './SearchInput.vue'

describe('SearchInput', () => {
  it('binds v-model value', () => {
    const w = mount(SearchInput, { props: { modelValue: 'query' } })
    expect((w.find('input').element as HTMLInputElement).value).toBe('query')
  })

  it('emits update:modelValue on input', async () => {
    const w = mount(SearchInput, { props: { modelValue: '' } })
    await w.find('input').setValue('test')
    const emitted = w.emitted('update:modelValue')!
    expect(emitted[emitted.length - 1]).toEqual(['test'])
  })

  it('uses default placeholder "Search\u2026"', () => {
    const w = mount(SearchInput)
    expect(w.find('input').attributes('placeholder')).toBe('Search\u2026')
  })

  it('accepts custom placeholder', () => {
    const w = mount(SearchInput, { props: { placeholder: 'Find items...' } })
    expect(w.find('input').attributes('placeholder')).toBe('Find items...')
  })

  it('applies default maxWidth of 340px', () => {
    const w = mount(SearchInput)
    expect(w.find('input').attributes('style')).toContain('max-width: 340px')
  })

  it('applies custom maxWidth', () => {
    const w = mount(SearchInput, { props: { maxWidth: '500px' } })
    expect(w.find('input').attributes('style')).toContain('max-width: 500px')
  })

  it('defaults to the md size variant', () => {
    const w = mount(SearchInput)
    expect(w.find('input').classes()).toContain('size-md')
  })

  it('applies the sm size variant when requested', () => {
    const w = mount(SearchInput, { props: { size: 'sm' } })
    expect(w.find('input').classes()).toContain('size-sm')
  })
})
