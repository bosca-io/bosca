export const WORKOPS_NOTIFICATION_CHANNELS = ['IN_APP', 'EMAIL', 'WEBHOOK', 'SLACK'] as const

export type WorkOpsNotificationChannel = typeof WORKOPS_NOTIFICATION_CHANNELS[number]

export const DEFAULT_WORKOPS_NOTIFICATION_CHANNELS = ['IN_APP', 'EMAIL'] as const

export const WORKOPS_NOTIFICATION_EVENTS = [
  'TASK_CREATED',
  'TASK_UPDATED',
  'TASK_ASSIGNED',
  'TASK_RESOLVED',
  'TASK_CLOSED',
  'TASK_REOPENED',
  'TASK_COMMENTED',
  'TASK_COMMENT_EDITED',
  'TASK_COMMENT_DELETED',
  'TASK_TRANSITIONED',
  'TASK_LINKED',
  'TASK_DELETED',
  'WORKLOG_LOGGED',
  'WORKLOG_UPDATED',
  'TASK_DUE',
  'SLA_BREACHED',
  'SLA_AT_RISK',
  'MENTIONED',
  'WATCH_ADDED',
  'WATCH_REMOVED',
  'SPRINT_STARTED',
  'SPRINT_CLOSED',
  'SPEC_CREATED',
  'SPEC_UPDATED',
  'SPEC_DELETED',
  'SPEC_TRANSITIONED',
  'SPEC_COMMENTED',
  'SPEC_TASKS_GENERATED',
  'REQUIREMENT_COMMENTED',
  'PIPELINE_APPROVAL_REQUESTED',
] as const

export interface WorkOpsMatrixPreference {
  channel: WorkOpsNotificationChannel
  type: string
  optedOut: boolean
}

const EVENT_LABELS: Record<string, string> = {
  TASK_COMMENTED: 'Task comment added',
  TASK_COMMENT_EDITED: 'Task comment edited',
  TASK_COMMENT_DELETED: 'Task comment deleted',
  WORKLOG_LOGGED: 'Work log added',
  WORKLOG_UPDATED: 'Work log updated',
  SPEC_COMMENTED: 'Specification comment added',
  SPEC_TASKS_GENERATED: 'Specification tasks generated',
  REQUIREMENT_COMMENTED: 'Requirement comment added',
}

function eventLabel(event: string): string {
  const override = EVENT_LABELS[event]
  if (override) return override
  const label = event.toLowerCase().replaceAll('_', ' ')
  const sentence = label.charAt(0).toUpperCase() + label.slice(1)
  return sentence
    .replace('Sla ', 'SLA ')
    .replace('Worklog', 'Work log')
}

export const WORKOPS_NOTIFICATION_TYPES = WORKOPS_NOTIFICATION_EVENTS.map((event, index) => ({
  key: event,
  name: eventLabel(event),
  description: null,
  optional: true,
  system: false,
  hidden: false,
  defaultEmailEnabled: true,
  defaultPushEnabled: true,
  displayOrder: index,
}))

export function channelsForWorkOpsEvent(
  eventChannels: Record<string, string[]>,
  event: string,
): WorkOpsNotificationChannel[] {
  if (!Object.prototype.hasOwnProperty.call(eventChannels, event) || !Array.isArray(eventChannels[event])) {
    return [...DEFAULT_WORKOPS_NOTIFICATION_CHANNELS]
  }
  return WORKOPS_NOTIFICATION_CHANNELS.filter(channel => eventChannels[event]?.includes(channel))
}

export function workOpsMatrixPreferences(
  eventChannels: Record<string, string[]>,
): WorkOpsMatrixPreference[] {
  return WORKOPS_NOTIFICATION_EVENTS.flatMap((event) => {
    const enabled = new Set(channelsForWorkOpsEvent(eventChannels, event))
    return WORKOPS_NOTIFICATION_CHANNELS.map(channel => ({
      channel,
      type: event,
      optedOut: !enabled.has(channel),
    }))
  })
}

export function setWorkOpsChannelEnabled(
  eventChannels: Record<string, string[]>,
  event: string,
  channel: string,
  enabled: boolean,
): Record<string, string[]> {
  if (!WORKOPS_NOTIFICATION_CHANNELS.includes(channel as WorkOpsNotificationChannel)) return eventChannels
  const next = new Set(channelsForWorkOpsEvent(eventChannels, event))
  if (enabled) next.add(channel as WorkOpsNotificationChannel)
  else next.delete(channel as WorkOpsNotificationChannel)
  return {
    ...eventChannels,
    [event]: WORKOPS_NOTIFICATION_CHANNELS.filter(candidate => next.has(candidate)),
  }
}
