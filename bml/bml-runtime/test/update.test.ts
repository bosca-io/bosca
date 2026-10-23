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
