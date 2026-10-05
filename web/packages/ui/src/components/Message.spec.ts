import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import Message from './Message.vue'

const defaultProps = {
  name: 'John Doe',
  color: '#ff9b5c',
  time: '10:30 AM',
  text: 'Hello world',
}

describe('Message', () => {
  it('renders the name', () => {
    const w = shallowMount(Message, { props: defaultProps })
    expect(w.find('.msg-name').text()).toBe('John Doe')
  })

  it('renders the time', () => {
    const w = shallowMount(Message, { props: defaultProps })
    expect(w.find('.msg-time').text()).toBe('10:30 AM')
  })

  it('renders the text', () => {
    const w = shallowMount(Message, { props: defaultProps })
    expect(w.find('.msg-text').text()).toBe('Hello world')
  })

  it('computes initials from name', () => {
    const w = shallowMount(Message, { props: defaultProps })
    expect(w.find('.msg-avatar').text()).toBe('JD')
  })

  it('computes single initial for one-word name', () => {
    const w = shallowMount(Message, { props: { ...defaultProps, name: 'Alice' } })
    expect(w.find('.msg-avatar').text()).toBe('A')
  })

  it('limits initials to two characters', () => {
    const w = shallowMount(Message, { props: { ...defaultProps, name: 'John Paul Jones' } })
    expect(w.find('.msg-avatar').text()).toBe('JP')
  })

  it('uppercases initials', () => {
    const w = shallowMount(Message, { props: { ...defaultProps, name: 'jane doe' } })
    expect(w.find('.msg-avatar').text()).toBe('JD')
  })

  it('applies color to the avatar background', () => {
    const w = shallowMount(Message, { props: { ...defaultProps, color: '#34d99a' } })
    expect(w.find('.msg-avatar').attributes('style')).toContain('background: #34d99a')
  })
})
