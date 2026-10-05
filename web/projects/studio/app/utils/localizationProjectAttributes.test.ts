import { describe, expect, it } from 'vitest'
import {
  linkedWorkOpsProjectId,
  withWorkOpsProjectId,
} from './localizationProjectAttributes'

describe('localization project attributes', () => {
  it('reads a string WorkOps project binding', () => {
    expect(linkedWorkOpsProjectId({ workopsProjectId: 'project-1' })).toBe('project-1')
    expect(linkedWorkOpsProjectId({ workopsProjectId: 12 })).toBeNull()
    expect(linkedWorkOpsProjectId(null)).toBeNull()
  })

  it('sets the binding without discarding unrelated attributes', () => {
    expect(withWorkOpsProjectId({ provider: { key: 'phrase' } }, 'project-2')).toEqual({
      provider: { key: 'phrase' },
      workopsProjectId: 'project-2',
    })
  })

  it('clears only the binding and returns null for an empty object', () => {
    expect(withWorkOpsProjectId({ workopsProjectId: 'project-1', provider: 'phrase' }, null)).toEqual({
      provider: 'phrase',
    })
    expect(withWorkOpsProjectId({ workopsProjectId: 'project-1' }, null)).toBeNull()
  })
})
