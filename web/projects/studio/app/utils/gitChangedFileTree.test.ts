import { describe, expect, it } from 'vitest'
import { buildChangedFileTree, changedFileParentPaths, type ChangedFileTreeEntry } from './gitChangedFileTree'

function entry(path: string): ChangedFileTreeEntry {
  return { path, changeType: 'MODIFY', viewed: false, commentCount: 0 }
}

describe('buildChangedFileTree', () => {
  it('groups deep paths into sorted directory and filename nodes', () => {
    const tree = buildChangedFileTree([
      entry('README.md'),
      entry('core/src/test/kotlin/bosca/db/mapper/Mapper10Test.kt'),
      entry('core/src/test/kotlin/bosca/db/ConnectionTest.kt'),
      entry('core/src/test/kotlin/bosca/db/mapper/Mapper2Test.kt'),
    ])

    expect(tree.map(node => [node.kind, node.name])).toEqual([
      ['directory', 'core'],
      ['file', 'README.md'],
    ])

    const core = tree[0]
    expect(core?.kind).toBe('directory')
    if (!core || core.kind !== 'directory') return

    const db = core.children[0]
    expect(db?.kind).toBe('directory')
    expect(db?.name).toBe('src')

    let current = db
    for (const segment of ['test', 'kotlin', 'bosca', 'db']) {
      expect(current?.kind).toBe('directory')
      if (!current || current.kind !== 'directory') return
      current = current.children.find(node => node.kind === 'directory' && node.name === segment)
    }
    expect(current?.kind).toBe('directory')
    if (!current || current.kind !== 'directory') return

    expect(current.children.map(node => [node.kind, node.name])).toEqual([
      ['directory', 'mapper'],
      ['file', 'ConnectionTest.kt'],
    ])
    const mapper = current.children[0]
    expect(mapper?.kind).toBe('directory')
    if (!mapper || mapper.kind !== 'directory') return
    expect(mapper.children.map(node => node.name)).toEqual(['Mapper2Test.kt', 'Mapper10Test.kt'])
  })
})

describe('changedFileParentPaths', () => {
  it('returns every directory ancestor from root to leaf', () => {
    expect(changedFileParentPaths('core/src/test/Example.kt')).toEqual([
      'core',
      'core/src',
      'core/src/test',
    ])
  })
})
