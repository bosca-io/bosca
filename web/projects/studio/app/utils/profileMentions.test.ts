import { describe, expect, it } from 'vitest'
import { findActiveProfileMention, replaceProfileMention } from './profileMentions'

describe('profile mentions', () => {
  it('finds a handle at the caret without treating email addresses as mentions', () => {
    expect(findActiveProfileMention('Ask @ada-love', 13)).toEqual({
      start: 4,
      end: 13,
      query: 'ada-love',
    })
    expect(findActiveProfileMention('ada@example.com', 15)).toBeNull()
    expect(findActiveProfileMention('Ask @@ada', 9)).toBeNull()
  })

  it('replaces only the active handle and returns the new caret position', () => {
    const value = 'Ask @ada about this'
    const mention = findActiveProfileMention(value, 8)
    expect(mention).not.toBeNull()

    expect(replaceProfileMention(value, mention!, 'ada-lovelace')).toEqual({
      value: 'Ask @ada-lovelace about this',
      cursor: 17,
    })
  })

  it('replaces the full handle when the caret is in the middle of it', () => {
    const value = 'Ask @ada-love today'
    const mention = findActiveProfileMention(value, 8)

    expect(mention).toEqual({ start: 4, end: 13, query: 'ada' })
    expect(replaceProfileMention(value, mention!, 'ada-lovelace').value)
      .toBe('Ask @ada-lovelace today')
  })

  it('stops autocomplete after whitespace in a display name', () => {
    expect(findActiveProfileMention('Ask @Ada Lovelace', 17)).toBeNull()
  })
})
