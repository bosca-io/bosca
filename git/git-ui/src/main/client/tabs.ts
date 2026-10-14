import type { ClientContext } from "@bosca/bml";

/** Fits navigation tabs to the available space and keeps the active tab visible. */
export function overflowTabs(ctx: ClientContext) {
  const row = ctx.ref<HTMLElement>("row");
  const more = ctx.ref<HTMLButtonElement>("more");
  const menu = ctx.ref<HTMLElement>("menu");
  if (!row || !more || !menu) return;
  const close = (focus = false) => {
    menu.hidden = true;
    more.setAttribute("aria-expanded", "false");
    if (focus) more.focus();
  };
  const layout = () => {
    const links = [...row.querySelectorAll<HTMLAnchorElement>(":scope > a")];
    links.forEach((link) => {
      link.hidden = false;
    });
    more.hidden = false;
    const gap = Number.parseFloat(getComputedStyle(row).columnGap) || 0;
    const widths = links.map(
      (link) => link.getBoundingClientRect().width + gap,
    );
    const available = row.getBoundingClientRect().width;
    const visible = new Set<number>();
    if (widths.reduce((sum, width) => sum + width, 0) - gap <= available) {
      links.forEach((_, index) => visible.add(index));
    } else {
      let remaining = available - more.getBoundingClientRect().width - gap;
      const active = links.findIndex(
        (link) => link.getAttribute("aria-current") === "page",
      );
      if (active >= 0) {
        visible.add(active);
        remaining -= widths[active];
      }
      for (let index = 0; index < links.length; index++) {
        if (index === active) continue;
        if (widths[index] > remaining) break;
        visible.add(index);
        remaining -= widths[index];
      }
    }
    menu.replaceChildren();
    links.forEach((link, index) => {
      link.hidden = !visible.has(index);
      if (link.hidden) {
        const entry = link.cloneNode(true) as HTMLAnchorElement;
        entry.hidden = false;
        menu.append(entry);
      }
    });
    more.hidden = visible.size === links.length;
    if (more.hidden) close();
  };
  const menuId = `tabs-${crypto.randomUUID()}`;
  menu.id = menuId;
  more.setAttribute("aria-controls", menuId);
  ctx.on(more, "click", () => {
    menu.hidden = !menu.hidden;
    more.setAttribute("aria-expanded", String(!menu.hidden));
    if (!menu.hidden) menu.querySelector<HTMLElement>("a")?.focus();
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", (event) => {
    if (event.key === "Escape" && !menu.hidden) {
      event.preventDefault();
      close(true);
    }
  });
  ctx.on<PointerEvent>(document, "pointerdown", (event) => {
    if (event.target instanceof Node && !ctx.root.contains(event.target))
      close();
  });
  const observer = new ResizeObserver(layout);
  observer.observe(row);
  ctx.onUnmount(() => observer.disconnect());
  layout();
}
