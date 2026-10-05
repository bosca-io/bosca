import { describe, expect, it } from 'vitest'
import {
  channelsForWorkOpsEvent,
  setWorkOpsChannelEnabled,
  WORKOPS_NOTIFICATION_TYPES,
  workOpsMatrixPreferences,
} from './workopsNotificationPreferences'

describe('WorkOps notification preferences', () => {
  it('shows in-app and email enabled for an event without an override', () => {
    expect(channelsForWorkOpsEvent({}, 'TASK_COMMENTED')).toEqual(['IN_APP', 'EMAIL'])

    const preferences = workOpsMatrixPreferences({})
    expect(preferences.find(p => p.type === 'TASK_COMMENTED' && p.channel === 'EMAIL')?.optedOut).toBe(false)
    expect(preferences.find(p => p.type === 'TASK_COMMENTED' && p.channel === 'SLACK')?.optedOut).toBe(true)
  })

  it('materializes the defaults before changing one channel', () => {
    const updated = setWorkOpsChannelEnabled({}, 'TASK_COMMENTED', 'EMAIL', false)

    expect(updated.TASK_COMMENTED).toEqual(['IN_APP'])
  })

  it('preserves an explicit empty event override', () => {
    expect(channelsForWorkOpsEvent({ TASK_COMMENTED: [] }, 'TASK_COMMENTED')).toEqual([])
  })

  it('uses readable event labels', () => {
    expect(WORKOPS_NOTIFICATION_TYPES.find(type => type.key === 'SLA_BREACHED')?.name).toBe('SLA breached')
    expect(WORKOPS_NOTIFICATION_TYPES.find(type => type.key === 'WORKLOG_LOGGED')?.name).toBe('Work log added')
  })
})
