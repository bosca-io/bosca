import type { ClientContext } from "@bosca/bml";

/** Flat ARIA tree presentation; server consumers retain ownership of folder loading. */
export function keyboardTree(ctx: ClientContext) {
  const collapsed = new Set<string>();
  let focusedPath = "";
  let ownedFocus = false;
  let childOf = "";
  let search = "";
  let searchedAt = 0;
  const rows = () =>
    [...ctx.root.querySelectorAll<HTMLElement>('[role="treeitem"]')].filter(
      (row) => !row.hidden,
    );
  const control = (row?: HTMLElement) =>
    row?.querySelector<HTMLElement>(".ui-tree-row");
  const focus = (row?: HTMLElement) => {
    if (!row) return;
    focusedPath = row.dataset.path ?? "";
    for (const entry of rows()) {
      const target = control(entry);
      if (target) target.tabIndex = entry === row ? 0 : -1;
    }
    control(row)?.focus();
  };
  const sync = () => {
    if (ctx.root.hasAttribute("data-local")) {
      for (const row of ctx.root.querySelectorAll<HTMLElement>(
        '[role="treeitem"]',
      )) {
        const path = row.dataset.path ?? "";
        row.hidden = [...collapsed].some((parent) =>
          path.startsWith(`${parent}/`),
        );
        if (row.hasAttribute("aria-expanded")) {
          const expanded = !collapsed.has(path);
          row.setAttribute("aria-expanded", String(expanded));
          control(row)?.setAttribute(
            "aria-label",
            `${expanded ? "Collapse" : "Expand"} ${row.querySelector(".tree-label")?.textContent?.trim() ?? path}`,
          );
        }
      }
    }
    const visible = rows();
    let active =
      visible.find((row) => row.dataset.path === focusedPath) ??
      visible.find((row) => row.getAttribute("aria-selected") === "true") ??
      visible[0];
    for (const row of visible) {
      const target = control(row);
      if (target) target.tabIndex = row === active ? 0 : -1;
    }
    if (ownedFocus && document.activeElement === document.body)
      control(active)?.focus();
    if (childOf) {
      const parent = visible.find((row) => row.dataset.path === childOf);
      if (parent?.getAttribute("aria-expanded") === "true") {
        active =
          visible.find((row) => row.dataset.path?.startsWith(`${childOf}/`)) ??
          parent;
        childOf = "";
        focus(active);
      }
    }
  };
  ctx.on<FocusEvent>(ctx.root, "focusin", (event) => {
    const row =
      event.target instanceof Element
        ? event.target.closest<HTMLElement>('[role="treeitem"]')
        : null;
    if (row) {
      focusedPath = row.dataset.path ?? "";
      ownedFocus = true;
      sync();
    }
  });
  ctx.on<FocusEvent>(ctx.root, "focusout", (event) => {
    if (
      event.relatedTarget instanceof Node &&
      !ctx.root.contains(event.relatedTarget)
    )
      ownedFocus = false;
  });
  ctx.on<MouseEvent>(ctx.root, "click", (event) => {
    const target =
      event.target instanceof Element
        ? event.target.closest<HTMLElement>("button.ui-tree-row")
        : null;
    const row = target?.closest<HTMLElement>('[role="treeitem"]');
    if (!row) return;
    focusedPath = row.dataset.path ?? "";
    if (ctx.root.hasAttribute("data-local")) {
      if (collapsed.has(focusedPath)) collapsed.delete(focusedPath);
      else collapsed.add(focusedPath);
      sync();
    }
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", (event) => {
    const row =
      event.target instanceof Element
        ? event.target.closest<HTMLElement>('[role="treeitem"]')
        : null;
    if (!row || event.altKey || event.ctrlKey || event.metaKey) return;
    const visible = rows();
    const index = visible.indexOf(row);
    const path = row.dataset.path ?? "";
    const expanded = row.getAttribute("aria-expanded");
    const parent = visible.find(
      (entry) =>
        entry.dataset.path === path.substring(0, path.lastIndexOf("/")),
    );
    if (
      [
        "ArrowDown",
        "ArrowUp",
        "Home",
        "End",
        "ArrowRight",
        "ArrowLeft",
      ].includes(event.key)
    ) {
      event.preventDefault();
      if (event.key === "ArrowDown")
        focus(visible[Math.min(index + 1, visible.length - 1)]);
      if (event.key === "ArrowUp") focus(visible[Math.max(index - 1, 0)]);
      if (event.key === "Home") focus(visible[0]);
      if (event.key === "End") focus(visible.at(-1));
      if (event.key === "ArrowRight" && expanded !== null) {
        childOf = path;
        if (expanded === "false") control(row)?.click();
        else sync();
      }
      if (event.key === "ArrowLeft") {
        if (expanded === "true") control(row)?.click();
        else focus(parent);
      }
    } else if (event.key.length === 1 && event.key !== " ") {
      event.preventDefault();
      const now = Date.now();
      search =
        now - searchedAt > 600
          ? event.key.toLowerCase()
          : search + event.key.toLowerCase();
      searchedAt = now;
      const next = [
        ...visible.slice(index + 1),
        ...visible.slice(0, index + 1),
      ].find((entry) =>
        entry
          .querySelector(".tree-label")
          ?.textContent?.trim()
          .toLowerCase()
          .startsWith(search),
      );
      focus(next);
    }
  });
  ctx.onUpdate(sync);
  ctx.on<CustomEvent<{ target: Element }>>(document, "bml:update", (event) => {
    if (event.detail?.target.contains(ctx.root)) sync();
  });
  sync();
}
