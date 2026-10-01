export interface ChangedFileTreeEntry {
  path: string
  changeType: string
  viewed: boolean
  commentCount: number
}

export interface ChangedFileTreeDirectory {
  kind: 'directory'
  name: string
  path: string
  children: ChangedFileTreeNode[]
}

export interface ChangedFileTreeFile {
  kind: 'file'
  name: string
  path: string
  entry: ChangedFileTreeEntry
}

export type ChangedFileTreeNode = ChangedFileTreeDirectory | ChangedFileTreeFile

interface MutableDirectory {
  name: string
  path: string
  directories: Map<string, MutableDirectory>
  files: ChangedFileTreeFile[]
}

function sortNodes(nodes: ChangedFileTreeNode[]): ChangedFileTreeNode[] {
  return nodes.sort((left, right) => {
    if (left.kind !== right.kind) return left.kind === 'directory' ? -1 : 1
    return left.name.localeCompare(right.name, undefined, { numeric: true, sensitivity: 'base' })
  })
}

function finalizeDirectory(directory: MutableDirectory): ChangedFileTreeDirectory {
  return {
    kind: 'directory',
    name: directory.name,
    path: directory.path,
    children: sortNodes([
      ...Array.from(directory.directories.values(), finalizeDirectory),
      ...directory.files,
    ]),
  }
}

export function buildChangedFileTree(entries: ChangedFileTreeEntry[]): ChangedFileTreeNode[] {
  const root: MutableDirectory = {
    name: '',
    path: '',
    directories: new Map(),
    files: [],
  }

  for (const entry of entries) {
    const segments = entry.path.split('/').filter(Boolean)
    const fileName = segments.pop() || entry.path || 'unknown'
    let parent = root

    for (const segment of segments) {
      const path = parent.path ? `${parent.path}/${segment}` : segment
      let directory = parent.directories.get(segment)
      if (!directory) {
        directory = { name: segment, path, directories: new Map(), files: [] }
        parent.directories.set(segment, directory)
      }
      parent = directory
    }

    parent.files.push({ kind: 'file', name: fileName, path: entry.path, entry })
  }

  return sortNodes([
    ...Array.from(root.directories.values(), finalizeDirectory),
    ...root.files,
  ])
}

export function changedFileParentPaths(path: string): string[] {
  const segments = path.split('/').filter(Boolean)
  segments.pop()
  return segments.map((_, index) => segments.slice(0, index + 1).join('/'))
}
