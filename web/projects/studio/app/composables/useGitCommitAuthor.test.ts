import { describe, it, expect } from 'vitest'
import { resolveGitCommitAuthor } from './useGitCommitAuthor'

describe('resolveGitCommitAuthor', () => {
  it('returns name from profile.name and email from the bosca.profiles.email attribute', () => {
    const author = resolveGitCommitAuthor({
      name: 'Alice',
      slug: 'alice',
      attributes: [
        { typeId: 'bosca.profiles.email', attributes: { email: 'alice@example.com' } },
        { typeId: 'unrelated', attributes: { email: 'ignore@example.com' } },
      ],
    })
    expect(author).toEqual({ authorName: 'Alice', authorEmail: 'alice@example.com' })
  })

  it('falls back to slug when name is empty', () => {
    const author = resolveGitCommitAuthor({ name: '', slug: 'alice-slug', attributes: [] })
    expect(author.authorName).toBe('alice-slug')
  })

  it('falls back to a placeholder name when both name and slug are missing', () => {
    const author = resolveGitCommitAuthor({ attributes: [] })
    expect(author.authorName).toBe('Studio User')
  })

  it('falls back to a placeholder email when the email attribute is absent', () => {
    const author = resolveGitCommitAuthor({ name: 'Alice', attributes: [] })
    expect(author.authorEmail).toBe('studio@bosca.io')
  })

  it('falls back to a placeholder email when the email attribute value is not a string', () => {
    const author = resolveGitCommitAuthor({
      name: 'Alice',
      attributes: [
        // eslint-disable-next-line @typescript-eslint/no-explicit-any -- intentionally exercising a non-string value
        { typeId: 'bosca.profiles.email', attributes: { email: 42 as any } },
      ],
    })
    expect(author.authorEmail).toBe('studio@bosca.io')
  })

  it('falls back to a placeholder email when the email attribute value is an empty string', () => {
    const author = resolveGitCommitAuthor({
      name: 'Alice',
      attributes: [{ typeId: 'bosca.profiles.email', attributes: { email: '' } }],
    })
    expect(author.authorEmail).toBe('studio@bosca.io')
  })

  it('handles a null profile (e.g. server-side render with no auth)', () => {
    const author = resolveGitCommitAuthor(null)
    expect(author).toEqual({ authorName: 'Studio User', authorEmail: 'studio@bosca.io' })
  })

  it('handles an undefined profile', () => {
    const author = resolveGitCommitAuthor(undefined)
    expect(author).toEqual({ authorName: 'Studio User', authorEmail: 'studio@bosca.io' })
  })

  it('handles a profile with null attributes', () => {
    const author = resolveGitCommitAuthor({ name: 'Alice', attributes: null })
    expect(author).toEqual({ authorName: 'Alice', authorEmail: 'studio@bosca.io' })
  })
})
