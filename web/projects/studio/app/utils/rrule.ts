export const WEEKDAYS = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'] as const
export type Weekday = (typeof WEEKDAYS)[number]

export type RRuleFreq = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY'

export interface RRuleConfig {
  freq: RRuleFreq
  interval: number
  byDay: Weekday[]
  count: number | null
  until: Date | null
}

export function parseRRule(str: string): RRuleConfig {
  const config: RRuleConfig = {
    freq: 'DAILY',
    interval: 1,
    byDay: [],
    count: null,
    until: null,
  }

  const cleaned = str.replace(/^RRULE:/i, '')
  const parts = cleaned.split(';')

  for (const part of parts) {
    const [key, value] = part.split('=')
    if (!key || !value) continue

    switch (key.toUpperCase()) {
      case 'FREQ':
        config.freq = value.toUpperCase() as RRuleFreq
        break
      case 'INTERVAL':
        config.interval = parseInt(value, 10) || 1
        break
      case 'BYDAY':
        config.byDay = value.split(',').map((d) => d.trim().toUpperCase() as Weekday)
        break
      case 'COUNT':
        config.count = parseInt(value, 10) || null
        break
      case 'UNTIL':
        config.until = parseUntilDate(value)
        break
    }
  }

  return config
}

export function buildRRule(config: RRuleConfig): string {
  const parts: string[] = [`FREQ=${config.freq}`]

  if (config.interval > 1) {
    parts.push(`INTERVAL=${config.interval}`)
  }

  if (config.byDay.length > 0) {
    parts.push(`BYDAY=${config.byDay.join(',')}`)
  }

  if (config.count != null) {
    parts.push(`COUNT=${config.count}`)
  } else if (config.until) {
    parts.push(`UNTIL=${formatUntilDate(config.until)}`)
  }

  return parts.join(';')
}

export function formatUntilDate(date: Date): string {
  const y = date.getUTCFullYear()
  const m = (date.getUTCMonth() + 1).toString().padStart(2, '0')
  const d = date.getUTCDate().toString().padStart(2, '0')
  const h = date.getUTCHours().toString().padStart(2, '0')
  const min = date.getUTCMinutes().toString().padStart(2, '0')
  const s = date.getUTCSeconds().toString().padStart(2, '0')
  return `${y}${m}${d}T${h}${min}${s}Z`
}

export function parseUntilDate(str: string): Date | null {
  const match = str.match(/^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/)
  if (!match) return null
  return new Date(
    Date.UTC(
      parseInt(match[1]!, 10),
      parseInt(match[2]!, 10) - 1,
      parseInt(match[3]!, 10),
      parseInt(match[4]!, 10),
      parseInt(match[5]!, 10),
      parseInt(match[6]!, 10),
    ),
  )
}
