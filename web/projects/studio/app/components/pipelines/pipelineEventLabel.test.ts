import { describe, expect, it } from 'vitest'
import { pipelineEventLabel } from './pipelineEventLabel'

describe('pipeline event labels', () => {
  it.each([
    ['bosca.git.model.GitHubDelivery', 'GitHub Delivery'],
    ['bosca.git.model.GitHubSynchronizationFailed', 'GitHub Synchronization Failed'],
    ['GitHubDelivery', 'GitHub Delivery'],
    ['GitHub Delivery', 'GitHub Delivery'],
    ['bosca.git.model.RefUpdateEvent', 'Ref Update Event'],
    ['bosca.event.', 'bosca.event.'],
  ])('formats %s as %s', (event, label) => {
    expect(pipelineEventLabel(event)).toBe(label)
  })
})
