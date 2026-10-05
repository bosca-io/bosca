/**
 * Pure, side-effect-free helpers behind the generic pipeline node-settings editor: parse a setting's
 * declared default, resolve a setting's effective value, decide field visibility, coerce a raw input
 * into a set/unset action (preserving the editor's existing per-control rules), reorder a list, and
 * seed a freshly-added node's settings. Kept here (not in the Vue component) so they unit-test directly.
 */
import type { SettingMeta } from './pipelineNodeTypes'

/** A live settings object — the node's `data.settings`, a flat key→value map. */
export type Settings = Record<string, unknown>

/** Parse a setting's string-encoded `default` into a typed value for its control; undefined when none. */
export function parseDefault(setting: Pick<SettingMeta, 'control' | 'default'>): unknown {
  const d = setting.default
  if (d == null || d === '') return undefined
  switch (setting.control) {
    case 'BOOLEAN': return d === 'true'
    case 'INTEGER': { const n = Number.parseInt(d, 10); return Number.isFinite(n) ? n : undefined }
    case 'NUMBER': { const n = Number.parseFloat(d); return Number.isFinite(n) ? n : undefined }
    default: return d
  }
}

/** A setting's effective value: its stored value, else its parsed default. */
export function effectiveSettingValue(setting: SettingMeta | undefined, settings: Settings): unknown {
  if (!setting) return undefined
  const stored = settings[setting.name]
  return stored !== undefined ? stored : parseDefault(setting)
}

/**
 * Whether a setting is visible given the current settings. A `visibleWhenSetting` gate compares against
 * the controlling setting's EFFECTIVE value (stored value, or its default) — so a defaulted-but-absent
 * controller (e.g. `operation` defaulting to `add`) still shows the right dependent fields.
 */
export function isSettingVisible(setting: SettingMeta, settings: Settings, all: SettingMeta[]): boolean {
  if (!setting.visibleWhenSetting) return true
  const controller = all.find(s => s.name === setting.visibleWhenSetting)
  const eff = String(effectiveSettingValue(controller, settings) ?? '')
  // `visibleWhenEquals` may be a comma-separated list — the setting shows when the controller's value is any of them.
  const allowed = String(setting.visibleWhenEquals ?? '').split(',').map(s => s.trim())
  return allowed.includes(eff)
}

/** A write decision: store `value`, or omit the key so the node's compiled-in default re-applies. */
export type Coercion = { action: 'set', value: unknown } | { action: 'unset' }

/**
 * Coerce a control's raw input into a set/unset action, preserving today's per-control behavior:
 *  - numbers: store only when positive, else omit (so the field can sit empty mid-edit and the default,
 *    shown as the placeholder, applies) — mirrors the inspector's `setPositiveInt`;
 *  - booleans: store only when different from the declared default, else omit (subsumes the old
 *    inverted-default toggles; runtime-identical because the backend defaults the absent key the same);
 *  - lists: split on commas, store the array or omit when empty;
 *  - schema/group rows: store when non-empty, else omit;
 *  - text/enum/code/condition/reference: omit a blank optional (so the default applies), else store as-is.
 */
export function coerceControlValue(setting: SettingMeta, raw: unknown): Coercion {
  switch (setting.control) {
    case 'INTEGER': {
      const n = Number.parseInt(String(raw), 10)
      return n > 0 ? { action: 'set', value: n } : { action: 'unset' }
    }
    case 'NUMBER': {
      const n = Number.parseFloat(String(raw))
      return Number.isFinite(n) && n > 0 ? { action: 'set', value: n } : { action: 'unset' }
    }
    case 'BOOLEAN': {
      const on = raw === true
      return on === (parseDefault(setting) === true) ? { action: 'unset' } : { action: 'set', value: on }
    }
    case 'LIST': {
      const items = String(raw ?? '').split(',').map(s => s.trim()).filter(s => s.length > 0)
      return items.length > 0 ? { action: 'set', value: items } : { action: 'unset' }
    }
    case 'SCHEMA':
    case 'GROUP_LIST': {
      if (raw == null) return { action: 'unset' }
      if (Array.isArray(raw)) return raw.length > 0 ? { action: 'set', value: raw } : { action: 'unset' }
      if (typeof raw === 'object' && Object.keys(raw as object).length === 0) return { action: 'unset' }
      return { action: 'set', value: raw }
    }
    default: {
      // TEXT / TEXTAREA / ENUM / CODE / CONDITION / REFERENCE — string-valued.
      const s = typeof raw === 'string' ? raw : String(raw ?? '')
      // Store the raw string (no trim, so typing a trailing space isn't snapped away); a blank optional
      // is omitted so the default applies, a blank required is kept present so the node still deserializes.
      if (s.trim() === '') return setting.required ? { action: 'set', value: s } : { action: 'unset' }
      return { action: 'set', value: s }
    }
  }
}

/** Swap item `i` with its neighbour `i + delta`; a no-op (returns the same array) when out of bounds. */
export function moveItem<T>(arr: readonly T[], i: number, delta: number): T[] {
  const j = i + delta
  if (i < 0 || i >= arr.length || j < 0 || j >= arr.length) return arr.slice()
  const next = arr.slice()
  const a = next[i] as T
  next[i] = next[j] as T
  next[j] = a
  return next
}

/**
 * Seed a freshly-added node's settings from its declared defaults: every setting with a non-blank
 * `default` gets that value, and every required setting with no default gets an empty placeholder value
 * — so a required-no-default field (e.g. ExecuteJob's `jobName`, a Switch case's `label`) is present and
 * the node deserializes. Everything else stays absent and falls back to the node's compiled-in default.
 */
export function seedNodeDefaults(settings: SettingMeta[]): Settings {
  const seed: Settings = {}
  for (const s of settings) {
    const d = parseDefault(s)
    if (d !== undefined) { seed[s.name] = d; continue }
    if (s.required) {
      seed[s.name] = s.control === 'INTEGER' || s.control === 'NUMBER'
        ? 0
        : s.control === 'LIST' || s.control === 'GROUP_LIST'
          ? []
          : ''
    }
  }
  return seed
}
