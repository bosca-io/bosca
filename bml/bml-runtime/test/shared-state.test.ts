import { describe, expect, it } from "vitest"
import { sharedState } from "../src/shared-state"

describe("sharedState", () => {
  it("creates the page-wide state once and returns the same object to every caller", () => {
    const first = sharedState("@bosca/bml/test/shared-once", () => ({ count: 0 }))
    first.count++
    const second = sharedState("@bosca/bml/test/shared-once", () => ({ count: 0 }))
    expect(second).toBe(first)
    expect(second.count).toBe(1)
  })

  it("adds fields a newer runtime expects to state created by an older bundle", () => {
    const older = sharedState("@bosca/bml/test/shared-upgrade", () => ({ count: 3 }))
    const newer = sharedState("@bosca/bml/test/shared-upgrade", () => ({ count: 0, retries: new Map<string, number>() }))

    expect(newer).toBe(older as unknown)
    expect(newer.count).toBe(3) // existing values are kept
    expect(newer.retries).toBeInstanceOf(Map) // missing fields are filled in
  })
})
