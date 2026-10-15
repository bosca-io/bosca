import type { ClientContext } from "@bosca/bml";

const WIDTH_KEY = "git-ui:diff-sidebar-width";
const DEFAULT_WIDTH = 300;
const MIN_WIDTH = 200;
const TOP_THRESHOLD = 600;

/** Collapsed file paths per page; island updates re-render files expanded and this restores them. */
const collapsedByPage = new Map<string, Set<string>>();

function collapsedPaths(): Set<string> {
  const key = location.pathname + location.search;
  let paths = collapsedByPage.get(key);
  if (!paths) {
    paths = new Set();
    collapsedByPage.set(key, paths);
  }
  return paths;
}

function readWidth(): number | null {
  try {
    const value = Number(localStorage.getItem(WIDTH_KEY));
    return Number.isFinite(value) && value > 0 ? value : null;
  } catch {
    // Storage may be unavailable; the default width applies.
    return null;
  }
}

function writeWidth(width: number | null) {
  try {
    if (width === null) localStorage.removeItem(WIDTH_KEY);
    else localStorage.setItem(WIDTH_KEY, String(Math.round(width)));
  } catch {
    // Storage may be unavailable; the width still applies to this page.
  }
}

/** The nearest ancestor that scrolls vertically, or the document. */
function scrollContainer(element: HTMLElement): HTMLElement {
  for (let parent = element.parentElement; parent; parent = parent.parentElement) {
    const overflow = getComputedStyle(parent).overflowY;
    if (overflow === "auto" || overflow === "scroll") return parent;
  }
  return (document.scrollingElement as HTMLElement | null) ?? document.documentElement;
}

/**
 * Review workspace behavior: collapsible files that keep their state across server updates,
 * the current file highlighted in the tree, a back-to-top control and a resizable file tree.
 */
export function diffWorkspace(ctx: ClientContext) {
  const root = ctx.root;
  const scroller = scrollContainer(root);
  const collapsed = collapsedPaths();
  const files = () =>
    [...root.querySelectorAll<HTMLElement>(".diff-file[data-path]")];
  const treeItem = (path: string) =>
    [...root.querySelectorAll<HTMLElement>('.diff-sidebar [role="treeitem"]')].find(
      (item) => item.dataset.path === path,
    );

  const setCollapsed = (file: HTMLElement, value: boolean) => {
    const path = file.dataset.path ?? "";
    const toggle = file.querySelector<HTMLElement>(".diff-file-toggle");
    const body = file.querySelector<HTMLElement>(".diff-file-body");
    file.toggleAttribute("data-collapsed", value);
    if (body) body.hidden = value;
    toggle?.setAttribute("aria-expanded", String(!value));
    toggle?.setAttribute("aria-label", `${value ? "Expand" : "Collapse"} ${path}`);
    if (value) collapsed.add(path);
    else collapsed.delete(path);
  };
  const restore = () => {
    for (const file of files())
      if (collapsed.has(file.dataset.path ?? "")) setCollapsed(file, true);
  };

  // Keep a collapsing file's header in view so the next file follows it directly.
  const keepInView = (file: HTMLElement) => {
    const top = file.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
    if (top < 0) scroller.scrollTop += top;
  };

  ctx.on<MouseEvent>(root, "click", (event) => {
    const target = event.target instanceof Element ? event.target : null;
    if (!target) return;
    const toggle = target.closest<HTMLElement>(".diff-file-toggle");
    if (toggle) {
      const file = toggle.closest<HTMLElement>(".diff-file");
      if (file) {
        const value = !file.hasAttribute("data-collapsed");
        setCollapsed(file, value);
        if (value) keepInView(file);
      }
      return;
    }
    if (target.closest("[data-diff-collapse-all]")) {
      for (const file of files()) setCollapsed(file, true);
      return;
    }
    if (target.closest("[data-diff-expand-all]")) {
      for (const file of files()) setCollapsed(file, false);
      return;
    }
    const link = target.closest<HTMLAnchorElement>('.diff-sidebar a[href^="#"]');
    if (link) {
      const path = link.closest<HTMLElement>('[role="treeitem"]')?.dataset.path;
      const file = files().find((entry) => entry.dataset.path === path);
      if (file?.hasAttribute("data-collapsed")) setCollapsed(file, false);
    }
  });

  // Highlight the file at the top of the viewport in the tree.
  let current = "";
  const select = (path: string) => {
    if (path === current) return;
    current = path;
    for (const item of root.querySelectorAll<HTMLElement>(
      '.diff-sidebar [role="treeitem"]',
    ))
      item.setAttribute("aria-selected", String(item.dataset.path === path));
    const item = treeItem(path);
    const list = root.querySelector<HTMLElement>(".sidebar-tree");
    if (item && list) {
      const itemBox = item.getBoundingClientRect();
      const listBox = list.getBoundingClientRect();
      if (itemBox.top < listBox.top) list.scrollTop += itemBox.top - listBox.top;
      else if (itemBox.bottom > listBox.bottom)
        list.scrollTop += itemBox.bottom - listBox.bottom;
    }
  };
  const top = ctx.ref<HTMLButtonElement>("top");
  let frame = 0;
  const onScroll = () => {
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      if (top) top.hidden = scroller.scrollTop < TOP_THRESHOLD;
      const edge = scroller.getBoundingClientRect().top + 48;
      const visible = files().filter(
        (file) => file.getBoundingClientRect().bottom > edge,
      );
      const file = visible[0];
      if (file) select(file.dataset.path ?? "");
    });
  };
  ctx.on(scroller, "scroll", onScroll, { passive: true });
  ctx.onUnmount(() => cancelAnimationFrame(frame));
  if (top)
    ctx.on(top, "click", () => {
      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      scroller.scrollTo({ top: 0, behavior: reduce ? "auto" : "smooth" });
      // The button hides at the top, so keyboard focus moves to the file tree.
      root
        .querySelector<HTMLElement>('.diff-sidebar .ui-tree-row[tabindex="0"]')
        ?.focus({ preventScroll: true });
    });

  // Resizable file tree; the width persists for this browser.
  const handle = ctx.ref<HTMLElement>("resize");
  const maxWidth = () => Math.max(MIN_WIDTH, Math.min(640, root.clientWidth * 0.6));
  const applyWidth = (width: number | null) => {
    if (width === null) {
      root.style.removeProperty("--diff-sidebar-width");
      handle?.setAttribute("aria-valuenow", String(DEFAULT_WIDTH));
      return;
    }
    const clamped = Math.round(Math.min(maxWidth(), Math.max(MIN_WIDTH, width)));
    root.style.setProperty("--diff-sidebar-width", `${clamped}px`);
    handle?.setAttribute("aria-valuenow", String(clamped));
  };
  if (handle) {
    handle.setAttribute("aria-valuemin", String(MIN_WIDTH));
    handle.setAttribute("aria-valuemax", String(Math.round(maxWidth())));
    applyWidth(readWidth());
    const sidebarWidth = () =>
      root.querySelector<HTMLElement>(".diff-sidebar")?.getBoundingClientRect().width ??
      DEFAULT_WIDTH;
    ctx.on<PointerEvent>(handle, "pointerdown", (event) => {
      if (event.button !== 0) return;
      event.preventDefault();
      const startX = event.clientX;
      const startWidth = sidebarWidth();
      handle.setPointerCapture(event.pointerId);
      handle.setAttribute("data-dragging", "");
      const move = (next: PointerEvent) =>
        applyWidth(startWidth + next.clientX - startX);
      const end = () => {
        handle.removeAttribute("data-dragging");
        handle.removeEventListener("pointermove", move);
        handle.removeEventListener("pointerup", end);
        handle.removeEventListener("pointercancel", end);
        writeWidth(sidebarWidth());
      };
      handle.addEventListener("pointermove", move);
      handle.addEventListener("pointerup", end);
      handle.addEventListener("pointercancel", end);
    });
    ctx.on(handle, "dblclick", () => {
      applyWidth(null);
      writeWidth(null);
    });
    ctx.on<KeyboardEvent>(handle, "keydown", (event) => {
      const step = event.shiftKey ? 64 : 16;
      let width: number | null = null;
      if (event.key === "ArrowLeft") width = sidebarWidth() - step;
      else if (event.key === "ArrowRight") width = sidebarWidth() + step;
      else if (event.key === "Home") width = MIN_WIDTH;
      else if (event.key === "End") width = maxWidth();
      else return;
      event.preventDefault();
      applyWidth(width);
      writeWidth(sidebarWidth());
    });
  }

  restore();
  ctx.onUpdate(restore);
  onScroll();
}
