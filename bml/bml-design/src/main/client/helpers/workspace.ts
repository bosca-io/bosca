import type { ClientContext } from "@bosca/bml";

// Nested workspaces can acquire the same background elements and dispose in either order.
const inertLocks = new WeakMap<HTMLElement, { owners: Set<HTMLElement>; original: boolean }>();

/** Owns fill-workspace expansion without replacing projected content.
 * Denied or unsupported native full screen retains viewport expansion.
 */
export function mountWorkspace(ctx: ClientContext) {
  const workspace = ctx.root;
  let expanded = false, busy = false, nativeActive = false, disposed = false;
  let trigger: HTMLButtonElement | null = null;
  const background = new Set<HTMLElement>();
  const buttons = () => Array.from(workspace.querySelectorAll<HTMLButtonElement>("[data-bml-fullscreen-button]"))
    .filter(button => button.closest("[data-bml-workspace]") === workspace);
  const focusControl = () => {
    if (!disposed && workspace.isConnected)
      (trigger?.isConnected ? trigger : buttons()[0])?.focus({ preventScroll: true });
  };
  const render = () => {
    workspace.toggleAttribute("data-bml-workspace-expanded", expanded);
    for (const button of buttons()) {
      button.hidden = false;
      button.setAttribute("aria-pressed", String(expanded));
      button.setAttribute("aria-busy", String(busy));
      const label = expanded ? button.dataset.fullscreenExitLabel ?? "Exit full screen" : button.dataset.fullscreenLabel ?? "Full screen";
      button.setAttribute("aria-label", label);
      button.dataset.tooltipContent = label;
      const enterIcon = button.querySelector<HTMLElement>("[data-fullscreen-enter-icon]");
      const exitIcon = button.querySelector<HTMLElement>("[data-fullscreen-exit-icon]");
      if (enterIcon) enterIcon.hidden = expanded;
      if (exitIcon) exitIcon.hidden = !expanded;
      button.dispatchEvent(new Event("bml:fullscreen-change"));
    }
  };
  const releaseBackground = () => {
    for (const element of background) {
      const lock = inertLocks.get(element);
      if (!lock) continue;
      lock.owners.delete(workspace);
      if (lock.owners.size === 0) {
        element.inert = lock.original;
        inertLocks.delete(element);
      }
    }
    background.clear();
  };
  const change = (value: boolean) => {
    if (expanded === value || disposed) return;
    const positions = [workspace, ...workspace.querySelectorAll<HTMLElement>("*")]
      .filter(element => element.scrollHeight > element.clientHeight || element.scrollWidth > element.clientWidth)
      .map(element => ({ element, top: element.scrollTop, left: element.scrollLeft }));
    expanded = value;
    render();
    if (value) {
      let branch = workspace;
      while (branch.parentElement) {
        for (const sibling of branch.parentElement.children) {
          if (sibling === branch || !(sibling instanceof HTMLElement)) continue;
          const lock = inertLocks.get(sibling) ?? { owners: new Set<HTMLElement>(), original: sibling.inert };
          lock.owners.add(workspace);
          inertLocks.set(sibling, lock);
          background.add(sibling);
          sibling.inert = true;
        }
        branch = branch.parentElement;
        if (branch === document.body) break;
      }
    } else releaseBackground();
    requestAnimationFrame(() => {
      if (disposed || !workspace.isConnected) return;
      for (const { element, top, left } of positions) element.scrollTo(left, top);
      focusControl();
    });
  };
  const exit = async () => {
    if (busy) return;
    busy = true;
    render();
    try {
      if (document.fullscreenElement === workspace) await document.exitFullscreen();
    } finally {
      nativeActive = false;
      change(false);
      busy = false;
      if (!disposed) render();
    }
  };
  ctx.on<MouseEvent>(workspace, "click", async event => {
    const button = (event.target as Element).closest<HTMLButtonElement>("[data-bml-fullscreen-button]");
    if (!button || button.closest("[data-bml-workspace]") !== workspace || busy) return;
    trigger = button;
    if (expanded) return exit();
    change(true);
    if (!workspace.requestFullscreen) return;
    busy = true;
    render();
    try {
      await workspace.requestFullscreen();
      if (disposed) {
        if (document.fullscreenElement === workspace) await document.exitFullscreen();
      } else nativeActive = document.fullscreenElement === workspace;
    } catch {
      // The viewport layout remains usable when the browser rejects native full screen.
    } finally {
      busy = false;
      if (!disposed) render();
    }
  });
  ctx.on(document, "fullscreenchange", () => {
    const current = document.fullscreenElement;
    if (current === workspace) nativeActive = true;
    else if (nativeActive && !workspace.contains(current)) {
      nativeActive = false;
      change(false);
    }
  });
  ctx.on<KeyboardEvent>(document, "keydown", event => {
    if (!expanded || event.key !== "Escape" || workspace.querySelector('[data-bml-workspace-expanded], dialog[open], :popover-open:not([role="tooltip"])')) return;
    event.preventDefault();
    void exit();
  }, true);
  ctx.onUpdate(render);
  ctx.onUnmount(() => {
    disposed = true;
    releaseBackground();
    if (document.fullscreenElement === workspace) void document.exitFullscreen();
  });
  render();
}
