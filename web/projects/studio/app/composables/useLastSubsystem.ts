const STORAGE_KEY = 'bosca:last-subsystem'
const RECENTS_KEY = 'bosca:recent-subsystems'
const RECENTS_MAX = 6

interface LastSubsystem {
  subsystem: string
  page: string
}

/**
 * Persists the last visited subsystem and page to localStorage so the app can
 * restore the user's position after logout/login or browser restart. Also keeps
 * a short most-recently-used list of subsystems for the navigation modal's
 * "Recent" row.
 */
export function useLastSubsystem() {
  function loadRecents(): string[] {
    try {
      const raw = localStorage.getItem(RECENTS_KEY)
      if (!raw) return []
      const parsed = JSON.parse(raw)
      return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : []
    } catch {
      return []
    }
  }

  function pushRecent(subsystem: string) {
    try {
      const next = [subsystem, ...loadRecents().filter(id => id !== subsystem)].slice(0, RECENTS_MAX)
      localStorage.setItem(RECENTS_KEY, JSON.stringify(next))
    } catch {
      // Storage full or unavailable — silently ignore
    }
  }

  function save(subsystem: string, page: string) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ subsystem, page }))
    } catch {
      // Storage full or unavailable — silently ignore
    }
    pushRecent(subsystem)
  }

  function load(): LastSubsystem | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return null
      const parsed = JSON.parse(raw) as LastSubsystem
      if (parsed.subsystem && parsed.page) return parsed
      return null
    } catch {
      return null
    }
  }

  return { save, load, recents: loadRecents }
}
