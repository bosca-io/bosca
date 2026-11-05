export interface HarnessState {
  state: string
  cancellationRequested: boolean
  failure?: string | null
}

export interface HarnessApproval {
  id: string
  expires: string
  request: {
    toolCall: { title?: string; kind?: string; locations?: { path: string }[] }
    permissions: { optionId: string; name: string; kind: string }[]
  }
}

export interface HarnessEvent {
  type: 'harness-turn'
  turnId: string
  generation: number
  epoch: number
  revision: number
  status: string
  text: string
  plan: { content: string; status: string; priority?: string }[]
  tools: { toolCallId: string; title?: string; status?: string; content?: Record<string, unknown>[] }[]
  content: Record<string, unknown>[]
}

export function decodeChatEvent(value: unknown): Record<string, unknown> | null {
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null
  } catch { return null }
}

export function harnessEvent(value: unknown): HarnessEvent | null {
  const event = decodeChatEvent(value)
  if (event?.type !== 'harness-turn' || typeof event.turnId !== 'string' || typeof event.status !== 'string') return null
  return {
    type: 'harness-turn', turnId: event.turnId, status: event.status,
    generation: Number(event.generation) || 0, epoch: Number(event.epoch) || 0, revision: Number(event.revision) || 0,
    text: typeof event.text === 'string' ? event.text : '',
    plan: Array.isArray(event.plan) ? event.plan : [],
    tools: Array.isArray(event.tools) ? event.tools : [],
    content: Array.isArray(event.content) ? event.content : [],
  }
}

export function newerHarnessEvent(current: HarnessEvent, incoming: HarnessEvent): HarnessEvent {
  for (const key of ['generation', 'epoch', 'revision'] as const) {
    if (current[key] !== incoming[key]) return current[key] > incoming[key] ? current : incoming
  }
  return incoming
}

export function harnessStateLabel(state: HarnessState | null | undefined): string {
  if (state?.cancellationRequested) return 'Stopping current work…'
  return ({ STARTING: 'Preparing session…', RESUMING: 'Restoring session…', READY: 'Ready',
    BUSY: 'Kit is working…', WAITING_FOR_APPROVAL: 'Waiting for your decision',
    STOPPING: 'Saving session…', STOPPED: 'Session archived', FAILED: 'Session interrupted',
  } as Record<string, string>)[state?.state ?? ''] ?? ''
}

export function safeHarnessLink(value: unknown): string | null {
  if (typeof value !== 'string' || Array.from(value).some(character => character.charCodeAt(0) <= 32 || character === '\\')) return null
  const metadata = value.match(new RegExp(`^bosca://content/metadata/(${resourceUuid})$`))
  if (metadata) return `/cms/editor/${metadata[1]}`
  const collection = value.match(new RegExp(`^bosca://content/collections/(${resourceUuid})$`))
  if (collection) return `/cms/collections/${collection[1]}`
  const analytics = value.match(new RegExp(`^bosca://analytics/(queries|visualizations|dashboards)/(${resourceUuid})$`))
  if (analytics) return `/analytics/${analytics[1]}/${analytics[2]}`
  const pipeline = value.match(new RegExp(`^bosca://pipelines/(${resourceUuid})$`))
  if (pipeline) return `/pipelines/${pipeline[1]}`
  const pipelineRun = value.match(new RegExp(`^bosca://pipelines/runs/(${resourceUuid})$`))
  if (pipelineRun) return `/pipelines/runs?runId=${pipelineRun[1]}`
  if (value.startsWith('/') && !value.startsWith('//')) return value
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? value : null
  } catch { return null }
}

const resourceUuid = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'

/** GraphQL envelopes are rendered from the embedded result; the URI is never fetched. */
export function harnessGraphQLResource(resource: Record<string, unknown> | undefined) {
  if (resource?.mimeType !== 'application/json' || typeof resource.uri !== 'string' || typeof resource.text !== 'string'
    || !/^bosca:\/\/graphql\/results\/(roots|type|search|execute)$/.test(resource.uri)) return null
  const value = decodeChatEvent(resource.text)
  if (!value || (!Object.hasOwn(value, 'data') && !Array.isArray(value.errors))) return null
  if (value.errors != null && (!Array.isArray(value.errors) || !value.errors.every(error =>
    error !== null && typeof error === 'object' && !Array.isArray(error) && typeof error.message === 'string'))) return null
  return value
}

/** Decode only Bosca's bounded query result resource. This never fetches the resource URI. */
export function harnessAnalyticsResource(resource: Record<string, unknown> | undefined) {
  if (resource?.mimeType !== 'application/json' || typeof resource.uri !== 'string' || typeof resource.text !== 'string') return null
  const reference = resource.uri.match(new RegExp(`^bosca://analytics/queries/(${resourceUuid}|by-key/[^/?#]+)/results$`))?.[1]
  const sqlResult = new RegExp(`^bosca://analytics/sql/${resourceUuid}/results$`).test(resource.uri)
  if (!reference && !sqlResult) return null
  try {
    const key = reference?.startsWith('by-key/') ? decodeURIComponent(reference.slice(7)) : null
    if (key !== null && (!key || Array.from(key).some(character => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127))) return null
    const value = decodeChatEvent(resource.text)
    if (!value || !Array.isArray(value.records) || value.records.length > 1000
      || !value.records.every(row => row !== null && typeof row === 'object' && !Array.isArray(row))
      || value.returnedRecords !== value.records.length || typeof value.truncated !== 'boolean'
      || typeof value.cached !== 'boolean' || (value.refreshedAt != null && typeof value.refreshedAt !== 'string')) return null
    const evidence = sqlResult ? null : decodeChatEvent(value.evidence)
    if (value.stale != null && typeof value.stale !== 'boolean') return null
    if (!sqlResult && value.evidence != null && (!evidence
      || typeof evidence.queryId !== 'string' || !new RegExp(`^${resourceUuid}$`).test(evidence.queryId)
      || typeof evidence.sql !== 'string' || !evidence.sql.trim()
      || typeof evidence.executedAt !== 'string' || typeof evidence.durationMs !== 'number' || evidence.durationMs < 0
      || !Array.isArray(evidence.parameters) || !evidence.parameters.every((parameter, index) =>
        parameter && typeof parameter === 'object' && !Array.isArray(parameter)
        && parameter.position === index + 1 && typeof parameter.parameter === 'string'
        && ['STRING', 'INTEGER', 'FLOAT', 'BOOLEAN', 'DATE', 'TIME', 'DATETIME', 'ARRAY', 'OBJECT'].includes(parameter.type)
        && Object.hasOwn(parameter, 'value')))) return null
    if (sqlResult && (typeof value.sql !== 'string' || !value.sql.trim()
      || typeof value.executedAt !== 'string' || typeof value.durationMs !== 'number' || value.durationMs < 0
      || !['NUMBER', 'BAR', 'LINE', 'PIE', 'TABLE'].includes(String(value.visualizationType))
      || !value.configuration || typeof value.configuration !== 'object' || Array.isArray(value.configuration)
      || !Object.keys(value.configuration).every(key => ['x', 'y', 'label', 'value', 'columns'].includes(key)))) return null
    return {
      sql: sqlResult ? value.sql as string : evidence?.sql as string | undefined,
      executedAt: sqlResult ? value.executedAt as string : evidence?.executedAt as string | undefined,
      durationMs: sqlResult ? value.durationMs as number : evidence?.durationMs as number | undefined,
      parameters: evidence?.parameters as Record<string, unknown>[] | undefined,
      stale: value.stale === true,
      visualizationType: sqlResult ? value.visualizationType as string : 'TABLE',
      configuration: sqlResult ? value.configuration as Record<string, unknown> : {},
      title: key ? `Query results: ${key}` : 'Query results',
      data: value.records as Record<string, unknown>[], truncated: value.truncated, cached: value.cached,
      refreshedAt: value.refreshedAt as string | null | undefined,
      queryHref: !sqlResult && key === null ? safeHarnessLink(`bosca://analytics/queries/${reference}`) : null,
    }
  } catch { return null }
}
export interface HarnessSettings {
  policyVersion: string
  effectiveProfile: string
  source: string
  canOverride: boolean
  choices: string[]
  mcpOptions?: { key: string; name: string; required: boolean }[]
  mcpServers?: string[]
  requestedMcpServers?: string[] | null
  mcpNotices?: string[]
  capabilityVersion?: string | null
  model?: string | null
  reasoning?: string | null
  modelSource?: string
  reasoningSource?: string
  requestedModel?: string | null
  requestedReasoning?: string | null
  models?: { value: string; name: string }[]
  reasoningOptions?: { value: string; name: string }[]
  requestedProfile?: string | null
  notice?: string | null
}

export interface HarnessContext {
  sources: { kind: string; id: string; title: string; ref?: string | null; path?: string | null; selected?: boolean }[]
  integrations: string[]
  notices: string[]
  selectionCount?: number
}

export interface HarnessSourceSelection {
  kind: 'TASK' | 'SPEC' | 'REQUIREMENT' | 'GIT_FILE'
  id: string
  ref?: string | null
  path?: string | null
}
