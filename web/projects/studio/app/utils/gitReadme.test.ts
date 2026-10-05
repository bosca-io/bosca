import { describe, expect, it } from 'vitest'
import { selectRepositoryReadme } from './gitReadme'

describe('selectRepositoryReadme', () => {
  it('selects a root markdown README case-insensitively by deterministic priority', () => {
    const selected = selectRepositoryReadme([
      { name: 'README', path: 'README', type: 'BLOB' },
      { name: 'ReadMe.Markdown', path: 'ReadMe.Markdown', type: 'BLOB' },
      { name: 'README.MD', path: 'README.MD', type: 'BLOB' },
    ])

    expect(selected?.path).toBe('README.MD')
  })

  it('ignores nested, directory, and unsupported README entries', () => {
    expect(selectRepositoryReadme([
      { name: 'README.md', path: 'docs/README.md', type: 'BLOB' },
      { name: 'README.md', path: 'README.md', type: 'TREE' },
      { name: 'README.rst', path: 'README.rst', type: 'BLOB' },
    ])).toBeNull()
  })
})
