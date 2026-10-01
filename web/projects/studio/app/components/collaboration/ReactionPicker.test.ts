import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ReactionPicker from './ReactionPicker.vue'

function cleanupBody() {
  while (document.body.firstChild) {
    document.body.removeChild(document.body.firstChild)
  }
}

afterEach(() => cleanupBody())

describe('ReactionPicker', () => {
  it('renders 12 emoji buttons', () => {
    mount(ReactionPicker, { props: { x: 100, y: 100 } })
    const btns = document.querySelectorAll('.emoji-btn')
    expect(btns.length).toBe(12)
  })

  it('includes common emojis', () => {
    mount(ReactionPicker, { props: { x: 100, y: 100 } })
    const btns = document.querySelectorAll('.emoji-btn')
    const emojis = Array.from(btns).map(b => b.textContent?.trim())
    expect(emojis).toContain('👍')
    expect(emojis).toContain('❤️')
    expect(emojis).toContain('🎉')
    expect(emojis).toContain('🔥')
  })

  it('emits select on emoji click', async () => {
    const wrapper = mount(ReactionPicker, { props: { x: 100, y: 100 } })
    const btn = document.querySelector('.emoji-btn') as HTMLElement
    btn?.click()
    expect(wrapper.emitted('select')).toBeTruthy()
    expect(wrapper.emitted('select')![0]![0]).toBe('👍')
  })

  it('positions at x,y', () => {
    mount(ReactionPicker, { props: { x: 200, y: 300 } })
    const picker = document.querySelector('.reaction-picker') as HTMLElement
    expect(picker?.style.left).toBe('200px')
    expect(picker?.style.top).toBe('300px')
  })
})
