import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ListPager from './ListPager.vue'

// Button is a Nuxt auto-imported component; stub it as a real <button> that forwards click + disabled.
const ButtonStub = {
  props: ['disabled'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>',
}

function mountListPager(props: { offset: number; pageSize: number; count: number; hasMore: boolean }) {
  return mount(ListPager, { props, global: { stubs: { Button: ButtonStub } } })
}

describe('ListPager', () => {
  it('renders nothing on a single full-or-partial first page', () => {
    const w = mountListPager({ offset: 0, pageSize: 25, count: 10, hasMore: false })
    expect(w.find('.pager').exists()).toBe(false)
  })

  it('shows the current range and disables Prev on the first page', () => {
    const w = mountListPager({ offset: 0, pageSize: 25, count: 25, hasMore: true })
    expect(w.find('.range').text()).toBe('1–25')
    const [prev, next] = w.findAll('button')
    expect(prev!.attributes('disabled')).toBeDefined()
    expect(next!.attributes('disabled')).toBeUndefined()
  })

  it('Next advances the offset by a page', async () => {
    const w = mountListPager({ offset: 0, pageSize: 25, count: 25, hasMore: true })
    await w.findAll('button')[1]!.trigger('click')
    expect(w.emitted('update:offset')![0]).toEqual([25])
  })

  it('Prev goes back a page and disables Next on the last page', async () => {
    const w = mountListPager({ offset: 25, pageSize: 25, count: 10, hasMore: false })
    expect(w.find('.range').text()).toBe('26–35')
    const [prev, next] = w.findAll('button')
    expect(next!.attributes('disabled')).toBeDefined()
    await prev!.trigger('click')
    expect(w.emitted('update:offset')![0]).toEqual([0])
  })
})
