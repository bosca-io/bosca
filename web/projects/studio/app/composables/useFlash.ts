const FLASH_KEY = 'bosca:flash'

/**
 * A one-shot "flash" message that survives a full-page reload.
 *
 * The auth→app handoff (e.g. account linking) navigates with `external: true` — a
 * hard reload that wipes in-memory state, so a success banner or queued toast on
 * the departing page is never seen. Stash the confirmation here before navigating;
 * the app reads and clears it on the next mount and surfaces it as a toast.
 *
 * Guarded on `window` (matching `useToast`) so it is a no-op during SSR.
 */
export function useFlash() {
  const available = () => typeof window !== 'undefined'

  function setFlash(message: string) {
    if (available()) window.sessionStorage.setItem(FLASH_KEY, message)
  }

  /** Returns the pending flash message (if any) and clears it, so it shows once. */
  function takeFlash(): string | null {
    if (!available()) return null
    const message = window.sessionStorage.getItem(FLASH_KEY)
    if (message) window.sessionStorage.removeItem(FLASH_KEY)
    return message
  }

  return { setFlash, takeFlash }
}
