import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import { defineComponent } from 'vue'
import Pagination from './Pagination.vue'

const ButtonStub = defineComponent({
  props: ['size', 'disabled'],
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
})

function mountPagination(props: { page: number; totalPages: number }) {
  return shallowMount(Pagination, {
    props,
    global: { stubs: { Button: ButtonStub } },
  })
}

describe('Pagination', () => {
  it('displays current page and total pages', () => {
    const w = mountPagination({ page: 2, totalPages: 10 })
    expect(w.find('.pagination-label').text()).toContain('2')
    expect(w.find('.pagination-label').text()).toContain('10')
  })

  it('disables prev button on page 1', () => {
    const w = mountPagination({ page: 1, totalPages: 5 })
    const buttons = w.findAllComponents(ButtonStub)
    const prevBtn = buttons[0]
    expect(prevBtn.props('disabled')).toBe(true)
  })

  it('enables prev button when page > 1', () => {
    const w = mountPagination({ page: 3, totalPages: 5 })
    const buttons = w.findAllComponents(ButtonStub)
    const prevBtn = buttons[0]
    expect(prevBtn.props('disabled')).toBe(false)
  })

  it('disables next button on last page', () => {
    const w = mountPagination({ page: 5, totalPages: 5 })
    const buttons = w.findAllComponents(ButtonStub)
    const nextBtn = buttons[1]
    expect(nextBtn.props('disabled')).toBe(true)
  })

  it('enables next button when not on last page', () => {
    const w = mountPagination({ page: 3, totalPages: 5 })
    const buttons = w.findAllComponents(ButtonStub)
    const nextBtn = buttons[1]
    expect(nextBtn.props('disabled')).toBe(false)
  })

  it('emits prev when prev button is clicked', async () => {
    const w = mountPagination({ page: 3, totalPages: 5 })
    const buttons = w.findAllComponents(ButtonStub)
    await buttons[0].trigger('click')
    expect(w.emitted('prev')).toHaveLength(1)
  })

  it('emits next when next button is clicked', async () => {
    const w = mountPagination({ page: 3, totalPages: 5 })
    const buttons = w.findAllComponents(ButtonStub)
    await buttons[1].trigger('click')
    expect(w.emitted('next')).toHaveLength(1)
  })
})
