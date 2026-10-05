import { toRaw } from 'vue'

export function toEditable(ct: Record<string, unknown> | null | undefined): Record<string, unknown> | null {
  if (!ct) return null
  const c: Record<string, unknown> = {}
  for (const attr of Object.keys(ct)) {
    const v = toRaw(ct[attr])
    if (Array.isArray(v)) {
      const a = []
      for (const item of v) {
        const x = toRaw(item)
        if (typeof x === 'object') {
          a.push(toEditable(x as Record<string, unknown>))
        } else {
          a.push(x)
        }
      }
      c[attr] = a
    } else if (v !== null && typeof v === 'object') {
      c[attr] = toEditable(v as Record<string, unknown>)
    } else {
      c[attr] = v
    }
  }
  return c
}
