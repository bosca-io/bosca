import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useProfileSearch } from './useProfileSearch'

const query = vi.fn()

vi.stubGlobal('useGraphQL', () => ({ query }))

beforeEach(() => {
  query.mockReset()
})

describe('useProfileSearch', () => {
  it('includes the profile email beside the name when requested', async () => {
    query.mockResolvedValue({
      search: {
        search: {
          documents: [{
            profile: {
              id: 'profile-1',
              name: 'Ada Lovelace',
              slug: 'ada',
              attributes: [{
                typeId: 'bosca.profiles.email',
                attributes: { email: 'ada@example.com' },
              }],
            },
          }],
        },
      },
    })

    const { searchProfiles } = useProfileSearch({ includeEmailInLabel: true })

    await expect(searchProfiles('ada')).resolves.toEqual([{
      value: 'profile-1',
      label: 'Ada Lovelace · ada@example.com',
    }])
    expect(query).toHaveBeenCalledWith(expect.anything(), {
      query: 'ada',
      limit: 20,
      offset: 0,
      includeEmail: true,
    })
  })

  it('keeps the existing name-only label by default', async () => {
    query.mockResolvedValue({
      search: {
        search: {
          documents: [{
            profile: {
              id: 'profile-1',
              name: 'Ada Lovelace',
              attributes: [{
                typeId: 'bosca.profiles.email',
                attributes: { email: 'ada@example.com' },
              }],
            },
          }],
        },
      },
    })

    const { searchProfiles } = useProfileSearch()

    await expect(searchProfiles('ada')).resolves.toEqual([{
      value: 'profile-1',
      label: 'Ada Lovelace',
    }])
    expect(query).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({
      includeEmail: false,
    }))
  })

  it('falls back to the profile name when the email attribute is absent', async () => {
    query.mockResolvedValue({
      search: {
        search: {
          documents: [{
            profile: {
              id: 'profile-1',
              name: 'Ada Lovelace',
              attributes: [],
            },
          }],
        },
      },
    })

    const { searchProfiles } = useProfileSearch({ includeEmailInLabel: true })

    await expect(searchProfiles('ada')).resolves.toEqual([{
      value: 'profile-1',
      label: 'Ada Lovelace',
    }])
  })

  it('returns canonical handles for mention autocomplete', async () => {
    query.mockResolvedValue({
      search: {
        search: {
          documents: [
            { profile: { id: 'profile-1', name: 'Ada Lovelace', slug: 'ada-lovelace' } },
            { profile: { id: 'profile-2', name: 'No Handle' } },
          ],
        },
      },
    })

    const { searchProfileMentions } = useProfileSearch()

    await expect(searchProfileMentions('ada')).resolves.toEqual([{
      id: 'profile-1',
      name: 'Ada Lovelace',
      handle: 'ada-lovelace',
    }])
  })
})
