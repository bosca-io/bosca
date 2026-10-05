/** JSON.stringify-compatible encoding that preserves JavaScript bigint values as exact JSON numbers. */
export function stringifyJson(value: unknown): string {
  const encoded = encodeJson(value, "", new Set<object>())
  if (encoded == null) throw new TypeError("BML request body is not JSON-serializable")
  return encoded
}

function encodeJson(value: unknown, key: string, stack: Set<object>): string | undefined {
  // Like JSON.stringify, honor toJSON on objects and on bigints: an application that installs
  // BigInt.prototype.toJSON (commonly to send IDs as strings) keeps getting that representation.
  if ((value != null && typeof value === "object") || typeof value === "bigint") {
    const toJSON = (value as { toJSON?: unknown }).toJSON
    if (typeof toJSON === "function") value = toJSON.call(value, key)
  }
  if (value != null && typeof value === "object") value = unboxPrimitive(value)

  switch (typeof value) {
    case "string":
      return JSON.stringify(value)
    case "boolean":
      return value ? "true" : "false"
    case "number":
      return Number.isFinite(value) ? JSON.stringify(value) : "null"
    case "bigint":
      return value.toString()
    case "object": {
      if (value == null) return "null"
      if (stack.has(value)) throw new TypeError("Converting circular structure to JSON")
      stack.add(value)
      try {
        if (Array.isArray(value)) {
          const items = Array.from(
            { length: value.length },
            (_, index) => encodeJson(value[index], String(index), stack) ?? "null",
          )
          return `[${items.join(",")}]`
        }
        const entries: string[] = []
        for (const property of Object.keys(value)) {
          const encodedProperty = encodeJson((value as Record<string, unknown>)[property], property, stack)
          if (encodedProperty != null) entries.push(`${JSON.stringify(property)}:${encodedProperty}`)
        }
        return `{${entries.join(",")}}`
      } finally {
        stack.delete(value)
      }
    }
    default:
      return undefined
  }
}

const stringValueOf = String.prototype.valueOf
const numberValueOf = Number.prototype.valueOf
const booleanValueOf = Boolean.prototype.valueOf
const bigintValueOf = BigInt.prototype.valueOf

/** Unboxes real primitive wrappers without trusting Symbol.toStringTag as proof of their type. */
function unboxPrimitive(value: object): unknown {
  if (value instanceof String) {
    try {
      stringValueOf.call(value)
    } catch {
      return value
    }
    return String(value)
  }
  if (value instanceof Number) {
    try {
      numberValueOf.call(value)
    } catch {
      return value
    }
    return Number(value)
  }
  if (value instanceof Boolean) {
    try {
      return booleanValueOf.call(value)
    } catch {
      return value
    }
  }
  if (value instanceof BigInt) {
    try {
      return bigintValueOf.call(value)
    } catch {
      return value
    }
  }
  return value
}
