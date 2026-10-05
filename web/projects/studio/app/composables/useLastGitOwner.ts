const STORAGE_KEY = 'bosca:last-git-owner'

interface SavedOwner {
  id: string
  label: string
}

/**
 * Persists the last selected git owner profile to localStorage so the
 * repositories page and create/import modals remember the user's choice.
 */
export function useLastGitOwner() {
  function save(owner: SavedOwner) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(owner))
    } catch {
      // Storage full or unavailable
    }
  }

  function load(): SavedOwner | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return null
      const parsed = JSON.parse(raw) as SavedOwner
      if (parsed.id && parsed.label) return parsed
      return null
    } catch {
      return null
    }
  }

  return { save, load }
}
