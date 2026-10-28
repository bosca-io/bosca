import type { ClientContext } from "@bosca/bml";

const recentKey = "bosca:recent-subsystems";

function recents(): string[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(recentKey) ?? "[]");
    return Array.isArray(value) ? value.filter((id): id is string => typeof id === "string").slice(0, 6) : [];
  } catch { return []; }
}

/** Remembers only subsystem identities; navigation targets always come from authored links. */
export function rememberSubsystem(id: string): void {
  if (!id) return;
  try { localStorage.setItem(recentKey, JSON.stringify([id, ...recents().filter(value => value !== id)].slice(0, 6))); }
  catch { /* Recent navigation remains optional when browser storage is unavailable. */ }
}

/** Reveals the authored recent links in visit order without creating browser-side markup. */
export function recentSubsystemLinks(root: HTMLElement, currentId = ""): number {
  const links = Array.from(root.querySelectorAll<HTMLElement>("[data-subsystem-id]"));
  const available = new Set(links.map(link => link.dataset.subsystemId));
  const ids = recents().filter(id => id !== currentId && available.has(id)).slice(0, 3);
  let count = 0;
  for (const link of links) {
    const index = ids.indexOf(link.dataset.subsystemId ?? "");
    link.hidden = index < 0;
    if (index >= 0) { link.style.order = String(index); count++; }
  }
  return count;
}

/** Studio-style local filtering, recent subsystems, and arrow/Enter navigation within a native modal. */
export function subsystemSwitcher(ctx: ClientContext): void {
  const panel = ctx.ref<HTMLDialogElement>("dialog");
  const filter = ctx.ref<HTMLInputElement>("filter");
  const body = ctx.ref<HTMLElement>("body");
  const empty = ctx.ref<HTMLElement>("empty");
  const queryLabel = ctx.ref<HTMLElement>("query");
  const trigger = ctx.ref<HTMLElement>("trigger");
  if (!panel || !filter || !body || !empty || !queryLabel || !trigger) return;
  const currentId = ctx.root.dataset.currentSubsystem ?? "";
  rememberSubsystem(currentId);
  const recentBlock = body.querySelector<HTMLElement>("[data-subsystem-recent-block]");
  let cursor = 0;
  let visible: HTMLAnchorElement[] = [];
  const highlight = () => {
    for (const link of body.querySelectorAll("[data-nav-cursor]")) link.removeAttribute("data-nav-cursor");
    visible[cursor]?.setAttribute("data-nav-cursor", "");
  };
  const update = () => {
    const query = filter.value.trim().toLocaleLowerCase();
    const recentCount = recentBlock ? recentSubsystemLinks(recentBlock, currentId) : 0;
    if (recentBlock) recentBlock.hidden = !!query || recentCount === 0;
    for (const group of body.querySelectorAll<HTMLElement>("[data-subsystem-category]")) {
      let count = 0;
      for (const link of group.querySelectorAll<HTMLElement>("[data-subsystem-id]")) {
        link.hidden = !!query && !`${link.dataset.subsystemLabel} ${link.dataset.subsystemDescription}`.toLocaleLowerCase().includes(query);
        if (!link.hidden) count++;
      }
      group.hidden = count === 0;
    }
    const recentLinks = Array.from(recentBlock?.querySelectorAll<HTMLAnchorElement>("a[data-subsystem-id]") ?? [])
      .filter(link => !link.hidden && !link.closest("[hidden]"))
      .sort((left, right) => Number(left.style.order) - Number(right.style.order));
    const categoryLinks = Array.from(body.querySelectorAll<HTMLAnchorElement>("[data-subsystem-category] a[data-subsystem-id]"))
      .filter(link => !link.hidden && !link.closest("[hidden]"));
    visible = [...recentLinks, ...categoryLinks];
    cursor = 0;
    queryLabel.textContent = filter.value;
    empty.hidden = visible.length !== 0;
    highlight();
  };
  ctx.on(trigger, "click", () => { filter.value = ""; update(); });
  ctx.on(filter, "input", update);
  ctx.on<KeyboardEvent>(panel, "keydown", event => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      cursor = Math.max(0, Math.min(visible.length - 1, cursor + (event.key === "ArrowDown" ? 1 : -1)));
      highlight();
      visible[cursor]?.scrollIntoView({ block: "nearest" });
    } else if (event.key === "Enter" && event.target === filter) {
      event.preventDefault();
      visible[cursor]?.click();
    }
  });
  ctx.on<PointerEvent>(body, "pointerover", event => {
    const link = event.target instanceof Element ? event.target.closest<HTMLAnchorElement>("a[data-subsystem-id]") : null;
    if (!link) return;
    const index = visible.indexOf(link);
    if (index >= 0) { cursor = index; highlight(); }
  });
  update();
}
