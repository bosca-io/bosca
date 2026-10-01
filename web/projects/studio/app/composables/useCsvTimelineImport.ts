import type { TimeEvent, TimeEventInput, TimeEventType } from '~/composables/useTimeEvents'
import { parseMs } from '~/utils/timeline'

export interface CsvImportProgress {
  status: 'idle' | 'parsing' | 'updating-events' | 'done' | 'error'
  totalRows: number
  currentRow: number
  error?: string
}

export function parseTimeValue(value: string): number | null {
  const trimmed = value.trim()
  if (!trimmed) return null

  const msResult = parseMs(trimmed)
  if (msResult !== null) return msResult

  const hhmmss = trimmed.match(/^(-?)(\d+):(\d{2}):(\d{2})(?:\.(\d{1,3}))?$/)
  if (hhmmss) {
    const sign = hhmmss[1]! === '-' ? -1 : 1
    const hours = parseInt(hhmmss[2]!, 10)
    const minutes = parseInt(hhmmss[3]!, 10)
    const seconds = parseInt(hhmmss[4]!, 10)
    if (minutes >= 60 || seconds >= 60) return null
    const millis = hhmmss[5] ? parseInt(hhmmss[5]!.padEnd(3, '0'), 10) : 0
    return sign * (hours * 3600000 + minutes * 60000 + seconds * 1000 + millis)
  }

  const secSuffix = trimmed.match(/^(-?\d+(?:\.\d+)?)s$/)
  if (secSuffix) {
    return Math.round(parseFloat(secSuffix[1]!) * 1000)
  }

  const num = Number(trimmed)
  if (!isNaN(num) && isFinite(num)) {
    return Math.round(num)
  }

  return null
}

export function parseCsv(text: string): string[][] {
  const cleaned = text.startsWith('\uFEFF') ? text.slice(1) : text
  const rows: string[][] = []
  let current = ''
  let inQuotes = false
  let fields: string[] = []

  for (let i = 0; i < cleaned.length; i++) {
    const ch = cleaned[i]
    const next = cleaned[i + 1]

    if (inQuotes) {
      if (ch === '"' && next === '"') {
        current += '"'
        i++
      } else if (ch === '"') {
        inQuotes = false
      } else {
        current += ch
      }
    } else {
      if (ch === '"') {
        inQuotes = true
      } else if (ch === ',') {
        fields.push(current)
        current = ''
      } else if (ch === '\n' || (ch === '\r' && next === '\n')) {
        fields.push(current)
        current = ''
        if (fields.some((f) => f.trim())) rows.push(fields)
        fields = []
        if (ch === '\r') i++
      } else {
        current += ch
      }
    }
  }

  fields.push(current)
  if (fields.some((f) => f.trim())) rows.push(fields)

  return rows
}

export interface AttributeColumn {
  col: number
  key: string
}

export interface ResolvedColumns {
  startCol: number
  endCol: number | null
  attributeCols: AttributeColumn[]
}

export function resolveColumns(headers: string[], attributeKeys?: string[]): ResolvedColumns {
  const lower = headers.map((h) => h.trim().toLowerCase().replace(/[_\s-]+/g, ''))

  const startNames = ['start', 'starttime', 'startoffset', 'startoffsetms', 'begin', 'from', 'time']
  const endNames = ['end', 'endtime', 'endoffset', 'endoffsetms', 'stop', 'to']

  let startCol = -1
  let endCol: number | null = null

  for (const name of startNames) {
    const idx = lower.indexOf(name)
    if (idx !== -1) { startCol = idx; break }
  }

  for (const name of endNames) {
    const idx = lower.indexOf(name)
    if (idx !== -1) { endCol = idx; break }
  }

  const timeCols = new Set<number>(
    [startCol, endCol].filter((c): c is number => c !== null && c !== -1),
  )
  const attributeCols: AttributeColumn[] = []

  if (attributeKeys && attributeKeys.length > 0) {
    const normalizedKeys = attributeKeys.map((k) =>
      k.trim().toLowerCase().replace(/[_\s-]+/g, ''),
    )
    for (let i = 0; i < lower.length; i++) {
      if (timeCols.has(i)) continue
      const keyIdx = normalizedKeys.indexOf(lower[i]!)
      if (keyIdx !== -1) {
        attributeCols.push({ col: i, key: attributeKeys[keyIdx]! })
      }
    }
  }

  if (startCol === -1 && attributeCols.length === 0) {
    startCol = 0
    if (endCol === null && headers.length > 1) {
      endCol = 1
    }
  }

  return { startCol, endCol, attributeCols }
}

function collectAttributeKeys(eventTypes: TimeEventType[]): string[] {
  const seen = new Set<string>()
  for (const eventType of eventTypes) {
    for (const attr of eventType.attributes || []) {
      seen.add(attr.key)
    }
  }
  return Array.from(seen)
}

export function findRelevantTypes(
  attributeCols: AttributeColumn[],
  eventTypes: TimeEventType[],
  existingEventTypeIds: Set<string>,
): TimeEventType[] {
  const csvKeys = new Set(attributeCols.map((c) => c.key))
  const matching = eventTypes.filter((et) => {
    const typeKeys = (et.attributes || []).map((a) => a.key)
    return typeKeys.some((k) => csvKeys.has(k))
  })
  if (matching.length === 0 && attributeCols.length === 0) {
    return eventTypes.filter((et) => existingEventTypeIds.has(et.id))
  }
  return matching
}

const MAX_CSV_SIZE = 10 * 1024 * 1024

export function useCsvTimelineImport(
  events: Ref<TimeEvent[]>,
  eventTypes: Ref<TimeEventType[]>,
  addEvent: (input: TimeEventInput, options?: { silent?: boolean }) => Promise<void>,
  editEvent: (id: string, input: TimeEventInput, options?: { silent?: boolean }) => Promise<void>,
  refresh: () => Promise<void>,
  activeTypeFilter?: Ref<string | null>,
) {
  const toast = useToast()

  const progress = ref<CsvImportProgress>({
    status: 'idle',
    totalRows: 0,
    currentRow: 0,
  })

  const isImporting = computed(
    () =>
      progress.value.status !== 'idle' &&
      progress.value.status !== 'done' &&
      progress.value.status !== 'error',
  )

  async function importCsv(file: File) {
    if (isImporting.value) return

    progress.value = { status: 'parsing', totalRows: 0, currentRow: 0 }

    try {
      if (file.size > MAX_CSV_SIZE) {
        throw new Error(
          `CSV file is too large (${(file.size / 1024 / 1024).toFixed(1)}MB). Maximum size is 10MB.`,
        )
      }
      const text = await file.text()
      const rows = parseCsv(text)

      if (rows.length < 2) {
        throw new Error('CSV must have a header row and at least one data row')
      }

      const headers = rows[0]!
      const dataRows = rows.slice(1)
      const attributeKeys = collectAttributeKeys(eventTypes.value)
      const { startCol, endCol, attributeCols } = resolveColumns(headers, attributeKeys)

      const hasTimeColumns = startCol !== -1
      const hasAttributeColumns = attributeCols.length > 0

      if (!hasTimeColumns && !hasAttributeColumns) {
        throw new Error('CSV headers do not match any known time columns or event attribute keys.')
      }

      const eventsByType = new Map<string, TimeEvent[]>()
      for (const evt of events.value) {
        const list = eventsByType.get(evt.type.id) || []
        list.push(evt)
        eventsByType.set(evt.type.id, list)
      }
      for (const [, list] of eventsByType) {
        list.sort((a, b) => a.sort - b.sort)
      }

      const existingTypeIds = new Set(eventsByType.keys())
      const filterId = activeTypeFilter?.value ?? null
      let relevantTypes: TimeEventType[]
      if (filterId) {
        const filtered = eventTypes.value.find((t) => t.id === filterId)
        relevantTypes = filtered ? [filtered] : []
      } else {
        relevantTypes = findRelevantTypes(attributeCols, eventTypes.value, existingTypeIds)
        if (relevantTypes.length > 1) {
          const names = relevantTypes.map((t) => t.name).join(', ')
          throw new Error(
            `CSV columns match multiple event types (${names}). ` +
              `Select a specific type tab before dropping the CSV.`,
          )
        }
      }

      if (relevantTypes.length === 0) {
        throw new Error('No event types match the CSV columns. Create an event type first.')
      }

      let maxSort = events.value.length > 0 ? Math.max(...events.value.map((e) => e.sort)) : -1

      const updates: { eventId: string; input: TimeEventInput }[] = []
      const creates: TimeEventInput[] = []

      const rowTimes: ({ startMs: number; endMs: number | null } | null)[] = []
      const skippedRows = new Set<number>()

      for (let i = 0; i < dataRows.length; i++) {
        const row = dataRows[i]!
        let startMs: number
        if (hasTimeColumns) {
          const parsed = parseTimeValue(row[startCol] || '')
          if (parsed === null) {
            skippedRows.add(i)
            rowTimes[i] = null
            continue
          }
          startMs = parsed
        } else {
          startMs = 0
        }

        let endMs: number | null = null
        if (hasTimeColumns && endCol !== null && row[endCol]) {
          endMs = parseTimeValue(row[endCol]!)
        }

        rowTimes[i] = { startMs, endMs }
      }

      for (const eventType of relevantTypes) {
        const typeEvents = eventsByType.get(eventType.id) || []
        const typeAttrKeys = new Set((eventType.attributes || []).map((a) => a.key))
        const typeOps: TimeEventInput[] = []

        let posIndex = 0
        for (let i = 0; i < dataRows.length; i++) {
          if (skippedRows.has(i)) continue
          const row = dataRows[i]!
          const times = rowTimes[i]!

          const existingEvent = posIndex < typeEvents.length ? typeEvents[posIndex] : null
          const startMs = hasTimeColumns ? times.startMs : (existingEvent?.startOffsetMs ?? times.startMs)
          const endMs = hasTimeColumns ? times.endMs : (existingEvent?.endOffsetMs ?? null)

          const attributes: Record<string, unknown> = { ...(existingEvent?.attributes ?? {}) }
          for (const { col, key } of attributeCols) {
            if (!typeAttrKeys.has(key)) continue
            const value = row[col]
            if (value !== undefined && value.trim() !== '') {
              attributes[key] = value.trim()
            }
          }

          const input: TimeEventInput = {
            type: eventType.id,
            startOffsetMs: startMs,
            endOffsetMs: endMs,
            sort: existingEvent?.sort ?? ++maxSort,
            attributes,
          }
          typeOps.push(input)

          if (existingEvent) {
            updates.push({ eventId: existingEvent.id, input })
          } else {
            creates.push(input)
          }

          posIndex++
        }

        for (let j = 0; j < typeOps.length - 1; j++) {
          if (typeOps[j]!.endOffsetMs == null) {
            typeOps[j]!.endOffsetMs = typeOps[j + 1]!.startOffsetMs
          }
        }
      }

      progress.value.totalRows = updates.length + creates.length
      progress.value.status = 'updating-events'

      const BATCH_SIZE = 10
      let updated = 0
      let created = 0
      let failedCount = 0

      for (let b = 0; b < updates.length; b += BATCH_SIZE) {
        const batch = updates.slice(b, b + BATCH_SIZE)
        const results = await Promise.allSettled(
          batch.map(({ eventId, input }) => editEvent(eventId, input, { silent: true })),
        )
        updated += results.filter((r) => r.status === 'fulfilled').length
        failedCount += results.length - results.filter((r) => r.status === 'fulfilled').length
        progress.value.currentRow = Math.min(b + BATCH_SIZE, updates.length)
      }

      for (let b = 0; b < creates.length; b += BATCH_SIZE) {
        const batch = creates.slice(b, b + BATCH_SIZE)
        const results = await Promise.allSettled(
          batch.map((input) => addEvent(input, { silent: true })),
        )
        created += results.filter((r) => r.status === 'fulfilled').length
        failedCount += results.length - results.filter((r) => r.status === 'fulfilled').length
        progress.value.currentRow = updates.length + Math.min(b + BATCH_SIZE, creates.length)
      }

      await refresh()

      progress.value = { status: 'done', totalRows: dataRows.length, currentRow: dataRows.length }

      const parts: string[] = []
      if (updated > 0) parts.push(`Updated ${updated} event${updated !== 1 ? 's' : ''}.`)
      if (created > 0) parts.push(`Created ${created} new event${created !== 1 ? 's' : ''}.`)
      if (skippedRows.size > 0) parts.push(`${skippedRows.size} row${skippedRows.size !== 1 ? 's' : ''} skipped (unparseable time).`)
      if (failedCount > 0) parts.push(`${failedCount} operation${failedCount !== 1 ? 's' : ''} failed.`)
      toast.add({ title: 'CSV imported', description: parts.join(' ') })
    } catch (e: unknown) {
      const errorMessage = e instanceof Error ? e.message : String(e)
      progress.value = { status: 'error', totalRows: 0, currentRow: 0, error: errorMessage }
      toast.add({
        title: 'CSV import failed',
        description: errorMessage,
        color: 'error',
      })
    }
  }

  return {
    progress,
    isImporting,
    importCsv,
  }
}
