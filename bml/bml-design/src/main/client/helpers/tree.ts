import type { ClientContext, IslandUpdate } from "@bosca/bml";

// Native controls may survive a server tree replacement while their children are loaded.
const enterChild = new WeakSet<HTMLElement>();

/** Keyboard behavior for flat path-based trees; callers own links and asynchronous children. */
export function keyboardTree(ctx: ClientContext) {
  const tree = ctx.root.querySelector<HTMLElement>('[role="tree"]');
  const collapsed = new Set<string>();
  const serverExpanded = new Map<string, boolean>();
  let focusedPath = "";
  let ownedFocus = false;
  let search = "";
  let searchedAt = 0;
  const allRows = () => [...ctx.root.querySelectorAll<HTMLElement>('[role="treeitem"]')]
    .filter(row => row.closest('[role="tree"]') === tree);
  const rows = () => allRows().filter(row => !row.hidden);
  const control = (row?: HTMLElement) => row?.querySelector<HTMLElement>(".ui-tree-row");
  const rowOf = (target: EventTarget | null) => target instanceof Element
    ? target.closest<HTMLElement>('[role="treeitem"]') : null;
  const focus = (row?: HTMLElement) => {
    if (!row) return;
    focusedPath = row.dataset.path ?? "";
    for (const entry of allRows()) {
      const target = control(entry);
      if (target) target.tabIndex = entry === row ? 0 : -1;
    }
    control(row)?.focus();
  };
  const sync = () => {
    const all = allRows();
    if (ctx.root.hasAttribute("data-local")) {
      const paths = new Set(all.map(row => row.dataset.path ?? ""));
      for (const path of serverExpanded.keys()) if (!paths.has(path)) {
        serverExpanded.delete(path);
        collapsed.delete(path);
      }
      for (const row of all.filter(row => row.hasAttribute("aria-expanded"))) {
        const path = row.dataset.path ?? "";
        const expanded = row.hasAttribute("data-expanded");
        if (serverExpanded.get(path) !== expanded) {
          if (expanded) collapsed.delete(path); else collapsed.add(path);
          serverExpanded.set(path, expanded);
        }
      }
      for (const row of all) {
        const path = row.dataset.path ?? "";
        row.hidden = [...collapsed].some(parent => path.startsWith(`${parent}/`));
        if (row.hasAttribute("aria-expanded")) {
          const expanded = !collapsed.has(path);
          row.setAttribute("aria-expanded", String(expanded));
          control(row)?.setAttribute("aria-label", `${expanded ? "Collapse" : "Expand"} ${row.querySelector(".tree-label")?.textContent?.trim() ?? path}`);
        }
      }
    }
    const visible = rows();
    const focused = rowOf(document.activeElement);
    if (focused && all.includes(focused)) {
      focusedPath = focused.dataset.path ?? "";
      ownedFocus = true;
    }
    const active = visible.find(row => row.dataset.path === focusedPath)
      ?? [...visible].reverse().find(row => focusedPath.startsWith(`${row.dataset.path}/`))
      ?? visible.find(row => row.getAttribute("aria-selected") === "true") ?? visible[0];
    for (const row of all) {
      const target = control(row);
      if (target) target.tabIndex = row === active ? 0 : -1;
    }
    if (ownedFocus && (document.activeElement === document.body || focused?.hidden)) control(active)?.focus();
    const parentControl = control(active);
    if (parentControl && document.activeElement === parentControl && enterChild.has(parentControl)
        && active?.getAttribute("aria-expanded") === "true") {
      const child = visible.find(row => row.dataset.path?.startsWith(`${active.dataset.path}/`));
      if (child) { enterChild.delete(parentControl); focus(child); }
    }
  };
  ctx.on<FocusEvent>(ctx.root, "focusin", event => {
    const row = rowOf(event.target);
    if (!row || !allRows().includes(row)) return;
    focusedPath = row.dataset.path ?? "";
    ownedFocus = true;
    sync();
  });
  ctx.on<FocusEvent>(ctx.root, "focusout", event => {
    if (event.relatedTarget instanceof Node) {
      if (!ctx.root.contains(event.relatedTarget)) ownedFocus = false;
      const target = event.target instanceof HTMLElement ? event.target : null;
      if (target) enterChild.delete(target);
    }
  });
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const target = event.target instanceof Element ? event.target.closest<HTMLElement>("button.ui-tree-row") : null;
    const row = rowOf(target);
    if (!row || !allRows().includes(row)) return;
    focusedPath = row.dataset.path ?? "";
    if (ctx.root.hasAttribute("data-local")) {
      if (collapsed.has(focusedPath)) collapsed.delete(focusedPath); else collapsed.add(focusedPath);
      sync();
    }
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", event => {
    const row = rowOf(event.target);
    if (!row || !rows().includes(row) || event.altKey || event.ctrlKey || event.metaKey || event.isComposing) return;
    const visible = rows(), index = visible.indexOf(row), path = row.dataset.path ?? "";
    const expanded = row.getAttribute("aria-expanded");
    const target = control(row);
    if (event.key !== "ArrowRight" && target) enterChild.delete(target);
    if (["ArrowDown", "ArrowUp", "Home", "End", "ArrowRight", "ArrowLeft"].includes(event.key)) {
      event.preventDefault();
      search = "";
      if (event.key === "ArrowDown") focus(visible[Math.min(index + 1, visible.length - 1)]);
      if (event.key === "ArrowUp") focus(visible[Math.max(index - 1, 0)]);
      if (event.key === "Home") focus(visible[0]);
      if (event.key === "End") focus(visible.at(-1));
      if (event.key === "ArrowRight" && expanded !== null && target) {
        enterChild.add(target);
        if (expanded === "false") target.click(); else sync();
      }
      if (event.key === "ArrowLeft") {
        if (expanded === "true") target?.click();
        else focus(visible.find(entry => entry.dataset.path === path.substring(0, path.lastIndexOf("/"))));
      }
    } else if (event.key.length === 1 && event.key !== " ") {
      event.preventDefault();
      const now = Date.now(), key = event.key.toLowerCase();
      search = now - searchedAt > 600 ? key : search + key;
      searchedAt = now;
      // Repeated letters cycle names beginning with that letter.
      const prefix = [...search].every(letter => letter === key) ? key : search;
      focus([...visible.slice(index + 1), ...visible.slice(0, index + 1)].find(entry =>
        entry.querySelector(".tree-label")?.textContent?.trim().toLowerCase().startsWith(prefix)));
    }
  });
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) sync();
  });
  sync();
}
