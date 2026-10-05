import { describe, expect, it } from "vitest"
import * as bml from "../src/index"

/** The package entry point re-exports the public API surface. */
describe("@bosca/bml entry point", () => {
  it("re-exports islands, graphql, contract, fragment, and actions", () => {
    expect(typeof bml.mountAll).toBe("function")
    expect(typeof bml.defineIsland).toBe("function")
    expect(typeof bml.bosca).toBe("object")
    expect(typeof bml.bmlContractCall).toBe("function")
    expect(typeof bml.renderFragment).toBe("function")
    expect(typeof bml.bindActions).toBe("function")
    expect(typeof bml.dispatchAction).toBe("function")
    expect("featureFlags" in bml).toBe(false)
  })
})
