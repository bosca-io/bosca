import { beforeEach, describe, expect, it, vi } from "vitest"
import { defineComponent, defineIsland, mountAll, unmountAll, type IslandContext } from "../src/island"

function island(name: string, id: string, props: Record<string, unknown>): HTMLElement {
  const el = document.createElement("div")
  el.dataset.bmlIsland = name
  el.dataset.bmlId = id
  el.dataset.bmlProps = JSON.stringify(props)
  const child = document.createElement("p")
  child.textContent = "ssr"
  el.append(child)
  document.body.append(el)
  return el
}

describe("island runtime", () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    document.body.replaceChildren()
  })

  it("mounts with root, id, props, and scoped", async () => {
    island("sortable", "sortable-1", { id: "42" })
    let captured: IslandContext | undefined
    defineIsland("sortable", (ctx) => {
      captured = ctx
    })
    await mountAll()

    expect(captured).toBeDefined()
    expect(captured!.id).toBe("sortable-1")
    expect(captured!.props.id).toBe("42")
    expect(captured!.scoped("items")).toBe("sortable-1:items")
    expect(captured!.root.querySelector("p")?.textContent).toBe("ssr")
  })

  it("replace swaps content and fires onUpdate", async () => {
    const el = island("box", "box-1", {})
    let updates = 0
    defineIsland("box", (ctx) => {
      ctx.onUpdate(() => {
        updates++
      })
      ctx.replace(ctx.root, "<span>new</span>")
    })
    await mountAll()

    expect(el.querySelector("span")?.textContent).toBe("new")
    expect(el.querySelector("p")).toBeNull()
    expect(updates).toBe(1)
  })

  it("replace retains data-bml-preserve roots and reports the update", async () => {
    const el = island("preserved", "preserved-1", {})
    const existing = document.createElement("div")
    existing.setAttribute("data-bml-preserve", "row-1")
    existing.textContent = "existing"
    el.replaceChildren(existing)
    let retained = 0
    let added = 0
    defineIsland("preserved", (ctx) => {
      ctx.onUpdate((update) => {
        retained = update.retained.length
        added = update.added.length
      })
      ctx.replace(
        ctx.root,
        '<div data-bml-preserve="row-1">server replacement</div><div data-bml-preserve="row-2">new</div>',
      )
    })
    await mountAll()

    expect(el.querySelector('[data-bml-preserve="row-1"]')).toBe(existing)
    expect(existing.textContent).toBe("existing")
    expect(retained).toBe(1)
    expect(added).toBe(1)
  })

  it("does not double-mount", async () => {
    island("counter", "counter-1", {})
    let mounts = 0
    defineIsland("counter", () => {
      mounts++
    })
    await mountAll()
    await mountAll()
    expect(mounts).toBe(1)
  })

  it("unmount runs onUnmount callbacks", async () => {
    island("res", "res-1", {})
    let cleaned = false
    defineIsland("res", (ctx) => {
      ctx.onUnmount(() => {
        cleaned = true
      })
    })
    await mountAll()
    unmountAll()
    expect(cleaned).toBe(true)
  })

  it("mounts wrapper-free components with root-local refs", async () => {
    const root = document.createElement("section")
    root.dataset.bmlComponent = "player-controls"
    const play = document.createElement("button")
    play.dataset.bmlRef = "play"
    root.append(play)
    document.body.append(root)
    let captured: HTMLButtonElement | null = null
    defineComponent<{ play: HTMLButtonElement }>("player-controls", (ctx) => {
      captured = ctx.refs.play
    })

    await mountAll()

    expect(captured).toBe(play)
    expect(root.parentElement).toBe(document.body)
  })

  it("mounts both client roles when a component root is also an island", async () => {
    const root = document.createElement("section")
    root.dataset.bmlComponent = "account-shell"
    root.dataset.bmlIsland = "account-status"
    document.body.append(root)
    const mounted: string[] = []
    const unmounted: string[] = []
    defineComponent("account-shell", (ctx) => {
      mounted.push("component")
      ctx.onUnmount(() => unmounted.push("component"))
    })
    defineIsland("account-status", (ctx) => {
      mounted.push("island")
      ctx.onUnmount(() => unmounted.push("island"))
    })

    await mountAll()
    await mountAll()
    unmountAll(root)

    expect(mounted).toEqual(["component", "island"])
    expect(unmounted).toEqual(["component", "island"])
  })

  it("automatically removes scoped listeners when a component leaves the document", async () => {
    const root = document.createElement("section")
    root.dataset.bmlComponent = "temporary-controls"
    document.body.append(root)
    let events = 0
    defineComponent("temporary-controls", (ctx) => {
      ctx.on(window, "temporary-event", () => { events++ })
    })
    await mountAll()

    window.dispatchEvent(new Event("temporary-event"))
    expect(events).toBe(1)
    root.remove()
    await new Promise((resolve) => setTimeout(resolve, 0))
    window.dispatchEvent(new Event("temporary-event"))

    expect(events).toBe(1)
  })

  it("dispatches a named model when a component owns multiple page-scoped models", async () => {
    const root = document.createElement("section")
    root.dataset.bmlComponent = "multi-state"
    for (const key of ["multi-state.first:a", "multi-state.second:a"]) {
      const view = document.createElement("div")
      view.dataset.bmlIsland = key
      view.dataset.bmlStateKey = key
      root.append(view)
      const state = document.createElement("script")
      state.type = "application/json"
      state.dataset.bmlStateKey = key
      state.textContent = "{}"
      root.append(state)
    }
    document.body.append(root)
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ state: "{}", html: null })))
    vi.stubGlobal("fetch", fetchMock)
    defineComponent("multi-state", async (ctx) => ctx.dispatch("second", "save"))

    await mountAll()

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(String(request.body)).stateKey).toBe("multi-state.second:a")
    vi.unstubAllGlobals()
  })

  it("dispatches a named component model from its own island key", async () => {
    const root = island("free-access-usage", "free-access-a", {})
    root.dataset.bmlStateKey = "free-access.access:a"
    const state = document.createElement("script")
    state.type = "application/json"
    state.dataset.bmlStateKey = "free-access.access:a"
    state.textContent = "{}"
    document.body.append(state)
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ state: "{}", html: null })))
    vi.stubGlobal("fetch", fetchMock)
    defineIsland("free-access-usage", async (ctx) => ctx.dispatch("access", "sync"))

    await mountAll()

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(String(request.body)).stateKey).toBe("free-access.access:a")
    vi.unstubAllGlobals()
  })

  it("dispatches a sibling model from the same component instance", async () => {
    const root = island("multi-first", "multi-a", {})
    root.dataset.bmlStateKey = "multi-state.first:a"
    for (const key of ["multi-state.first:a", "multi-state.second:a", "multi-state.second:b"]) {
      const state = document.createElement("script")
      state.type = "application/json"
      state.dataset.bmlStateKey = key
      state.textContent = "{}"
      document.body.append(state)
    }
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ state: "{}", html: null })))
    vi.stubGlobal("fetch", fetchMock)
    defineIsland("multi-first", async (ctx) => ctx.dispatch("second", "save"))

    await mountAll()

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(String(request.body)).stateKey).toBe("multi-state.second:a")
    vi.unstubAllGlobals()
  })

  it("dispatches headless site server state through its exact component marker", async () => {
    const root = document.createElement("section")
    root.dataset.bmlComponent = "account-state"
    document.body.append(root)
    const marker = document.createElement("script")
    marker.type = "application/json"
    marker.dataset.bmlStateKey = "account-state.account"
    marker.setAttribute("data-bml-site", "")
    marker.setAttribute("data-bml-server", "")
    document.body.append(marker)
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ state: null, html: null })))
    vi.stubGlobal("fetch", fetchMock)
    defineComponent("account-state", async (ctx) => ctx.dispatch("account", "sync"))

    await mountAll()

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(String(request.body))).toMatchObject({
      stateKey: "account-state.account",
      state: "",
      renderView: false,
    })
    vi.unstubAllGlobals()
  })
})
