import { describe, expect, it } from "vitest"
import { stringifyJson } from "../src/json"

describe("stringifyJson", () => {
  it("preserves bigint values as exact JSON numbers", () => {
    expect(stringifyJson({ id: 9007199254740993n, nested: [-9007199254740995n] }))
      .toBe('{"id":9007199254740993,"nested":[-9007199254740995]}')
  })

  it("retains ordinary JSON.stringify behavior", () => {
    const value = {
      present: "yes",
      omitted: undefined,
      array: [undefined, Number.NaN, , "after-hole"],
      date: new Date("2026-09-17T00:00:00Z"),
    }
    expect(stringifyJson(value)).toBe(JSON.stringify(value))
  })

  it("distinguishes tagged objects from real primitive wrappers", () => {
    const tagged = {
      field: "kept",
      valueOf: () => "substituted",
      [Symbol.toStringTag]: "String",
    }
    const boxed = new Number(7)
    boxed.valueOf = () => 8
    const throwingTag = Object.defineProperty(
      { field: "kept" },
      Symbol.toStringTag,
      { get: () => { throw new Error("tag getter must not run") } },
    )

    expect(stringifyJson(tagged)).toBe(JSON.stringify(tagged))
    expect(stringifyJson(boxed)).toBe(JSON.stringify(boxed))
    expect(stringifyJson(throwingTag)).toBe(JSON.stringify(throwingTag))
  })

  it("honors BigInt.prototype.toJSON the way JSON.stringify does", () => {
    const prototype = BigInt.prototype as unknown as { toJSON?: () => string }
    prototype.toJSON = function (this: bigint) { return this.toString() }
    try {
      expect(stringifyJson({ id: 9007199254740993n })).toBe('{"id":"9007199254740993"}')
      expect(stringifyJson({ id: 9007199254740993n })).toBe(JSON.stringify({ id: 9007199254740993n }))
    } finally {
      delete prototype.toJSON
    }
  })
})
