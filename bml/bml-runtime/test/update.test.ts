import { afterEach, describe, expect, it, vi } from "vitest"
import { notifyIslandUpdate, replaceChildrenFromHtml } from "../src/update"

afterEach(() => {
  document.body.replaceChildren()
  vi.restoreAllMocks()
})

describe("preserved native scrolling", () => {
  it("restores both axes on roots and descendants before notifying mounted helpers", () => {
    const target = document.createElement("div")
    target.innerHTML = '<div data-bml-preserve="tree"><div><button>Focused</button></div></div>'
    document.body.append(target)
    const root = target.firstElementChild as HTMLElement
    const nested = root.firstElementChild as HTMLElement
    const button = nested.firstElementChild as HTMLButtonElement
    root.scrollTop = 44
    root.scrollLeft = 18
    nested.scrollTop = 61
    nested.scrollLeft = 29
    button.focus()
    const replace = target.replaceChildren.bind(target)
    vi.spyOn(target, "replaceChildren").mockImplementation((...nodes) => {
      replace(...nodes)
      // Model the native offset loss when Chrome detaches the retained subtree.
      root.scrollTop = root.scrollLeft = nested.scrollTop = nested.scrollLeft = 0
    })
    const update = replaceChildrenFromHtml(target, '<div data-bml-preserve="tree" data-revision="2">New server children</div>')
    const observed = vi.fn(() => [root.scrollTop, root.scrollLeft, nested.scrollTop, nested.scrollLeft])
    target.addEventListener("bml:update", observed, { once: true })
    notifyIslandUpdate(update)
    expect(target.firstElementChild).toBe(root)
    expect(root.firstElementChild).toBe(nested)
    expect(root.dataset.revision).toBe("2")
    expect(document.activeElement).toBe(button)
    expect(observed.mock.results[0].value).toEqual([44, 18, 61, 29])
    expect(update.retained).toEqual([root])
  })

  it("does not transfer offsets to a replacement with a different preservation key", () => {
    const target = document.createElement("div")
    target.innerHTML = '<div data-bml-preserve="old">Old</div>'
    document.body.append(target)
    const old = target.firstElementChild as HTMLElement
    old.scrollTop = 44
    const update = replaceChildrenFromHtml(target, '<div data-bml-preserve="new">New</div>')
    expect((target.firstElementChild as HTMLElement).scrollTop).toBe(0)
    expect(update.retained).toEqual([])
    expect(update.added).toEqual([target.firstElementChild])
  })
})

describe("scroll-only preservation", () => {
  it("refreshes server content and restores both axes by key before update callbacks", () => {
    const target = document.createElement("div")
    target.innerHTML = '<section data-bml-scroll="content"><p>Old data</p></section><aside data-bml-scroll="sidebar">Old sidebar</aside>'
    document.body.append(target)
    const old = target.firstElementChild as HTMLElement
    old.scrollTop = 240
    old.scrollLeft = 32
    ;(target.lastElementChild as HTMLElement).scrollTop = 60
    const update = replaceChildrenFromHtml(target, '<aside data-bml-scroll="sidebar">New sidebar</aside><section data-bml-scroll="content"><p>Fresh data</p></section>')
    const fresh = target.lastElementChild as HTMLElement
    const observed = vi.fn(() => [fresh.textContent, fresh.scrollTop, fresh.scrollLeft])
    target.addEventListener("bml:update", observed, { once: true })
    notifyIslandUpdate(update)
    expect(fresh).not.toBe(old)
    expect(observed.mock.results[0].value).toEqual(["Fresh data", 240, 32])
    expect((target.firstElementChild as HTMLElement).scrollTop).toBe(60)
    expect(update.retained).toEqual([])
  })

  it.each([
    ['<section data-bml-scroll="other">New tab</section>', true],
    ['<article data-bml-scroll="content">Different kind</article>', true],
    ['<section data-bml-scroll="content">Explicit reset</section>', false],
  ])("starts fresh for a changed key, element kind or explicit reset: %s", (html, preserve) => {
    const target = document.createElement("div")
    target.innerHTML = '<section data-bml-scroll="content">Old data</section>'
    document.body.append(target)
    ;(target.firstElementChild as HTMLElement).scrollTop = 240
    replaceChildrenFromHtml(target, html, preserve)
    expect((target.firstElementChild as HTMLElement).scrollTop).toBe(0)
  })

  it("captures the latest position at replacement and scopes keys to the updated region", () => {
    const target = document.createElement("section")
    target.dataset.bmlScroll = "content"
    target.innerHTML = '<div data-bml-preserve="editor">Retained editor</div>'
    const sibling = document.createElement("section")
    sibling.dataset.bmlScroll = "content"
    sibling.scrollTop = 900
    document.body.append(target, sibling)
    target.scrollTop = 100
    // A reader can continue scrolling while a refresh response is in flight.
    target.scrollTop = 350
    const replace = target.replaceChildren.bind(target)
    vi.spyOn(target, "replaceChildren").mockImplementation((...nodes) => {
      replace(...nodes)
      target.scrollTop = 0
    })
    replaceChildrenFromHtml(target, '<div data-bml-preserve="editor">Updated editor</div><p>New metrics</p>')
    expect(target.scrollTop).toBe(350)
    expect(sibling.scrollTop).toBe(900)
    expect(target.textContent).toBe("Retained editorNew metrics")
  })
})

describe("native disclosure state", () => {
  it("retains open and closed choices while replacing refreshed content", () => {
    const target = document.createElement("div")
    target.innerHTML = '<details data-bml-disclosure="query" open><summary>SQL</summary>Old query</details><details data-bml-disclosure="counters"><summary>Counters</summary>Old counters</details>'
    document.body.append(target)
    const old = target.firstElementChild
    replaceChildrenFromHtml(target, '<details data-bml-disclosure="counters" open><summary>Counters</summary>Fresh counters</details><details data-bml-disclosure="query"><summary>SQL</summary>Fresh query</details>')
    expect((target.firstElementChild as HTMLDetailsElement).open).toBe(false)
    expect((target.lastElementChild as HTMLDetailsElement).open).toBe(true)
    expect(target.lastElementChild).not.toBe(old)
    expect(target.textContent).toContain("Fresh query")
  })

  it("restores disclosure geometry before restoring the scroller's offset", () => {
    const target = document.createElement("div")
    target.innerHTML = '<div data-bml-scroll="content"><details data-bml-disclosure="query" open><summary>SQL</summary>Old</details></div>'
    document.body.append(target)
    ;(target.firstElementChild as HTMLElement).scrollTop = 600
    const replace = target.replaceChildren.bind(target)
    let offset = 0
    vi.spyOn(target, "replaceChildren").mockImplementation((...nodes) => {
      replace(...nodes)
      Object.defineProperty(target.firstElementChild, "scrollTop", {
        get: () => offset,
        set: (value: number) => {
          // A collapsed disclosure would reduce the native scroll range.
          offset = (target.querySelector("details") as HTMLDetailsElement).open ? value : Math.min(250, value)
        },
      })
    })
    replaceChildrenFromHtml(target, '<div data-bml-scroll="content"><details data-bml-disclosure="query"><summary>SQL</summary>Updated</details></div>')
    expect((target.firstElementChild as HTMLElement).scrollTop).toBe(600)
  })

  it.each([
    ['<details data-bml-disclosure="different"><summary>New query</summary></details>', true],
    ['<details data-bml-disclosure="query"><summary>Reset</summary></details>', false],
  ])("uses the server's initial state for a new key or explicit reset", (html, preserve) => {
    const target = document.createElement("div")
    target.innerHTML = '<details data-bml-disclosure="query" open><summary>Old</summary></details>'
    document.body.append(target)
    replaceChildrenFromHtml(target, html, preserve)
    expect((target.firstElementChild as HTMLDetailsElement).open).toBe(false)
  })
})
