import { describe, it, expect } from 'vitest'
import { getWorkflowBadge, getWorkflowState, WORKFLOW_STATUS } from './workflowStatus'

describe('workflowStatus', () => {
  describe('WORKFLOW_STATUS', () => {
    it('contains all standard states', () => {
      expect(WORKFLOW_STATUS).toHaveProperty('draft')
      expect(WORKFLOW_STATUS).toHaveProperty('review')
      expect(WORKFLOW_STATUS).toHaveProperty('translate')
      expect(WORKFLOW_STATUS).toHaveProperty('scheduled')
      expect(WORKFLOW_STATUS).toHaveProperty('published')
      expect(WORKFLOW_STATUS).toHaveProperty('archived')
    })

    it('each entry has label and color', () => {
      for (const [, v] of Object.entries(WORKFLOW_STATUS)) {
        expect(v).toHaveProperty('label')
        expect(v).toHaveProperty('color')
        expect(v.color).toMatch(/^#/)
      }
    })
  })

  describe('getWorkflowBadge', () => {
    it('returns known state badge', () => {
      expect(getWorkflowBadge('published')).toEqual({ label: 'Published', color: '#2a9d6e' })
    })

    it('falls back for unknown state', () => {
      const badge = getWorkflowBadge('unknown')
      expect(badge.label).toBe('unknown')
      expect(badge.color).toBe('#6c7388')
    })
  })

  describe('getWorkflowState', () => {
    it('returns pending state when present', () => {
      expect(getWorkflowState({ state: 'draft', pending: 'review' })).toBe('review')
    })

    it('returns current state when no pending', () => {
      expect(getWorkflowState({ state: 'published', pending: null })).toBe('published')
    })

    it('returns draft for null workflow', () => {
      expect(getWorkflowState(null)).toBe('draft')
    })

    it('returns draft for undefined workflow', () => {
      expect(getWorkflowState(undefined)).toBe('draft')
    })
  })
})
