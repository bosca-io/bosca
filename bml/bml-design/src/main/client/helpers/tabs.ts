import type { ClientContext, IslandUpdate } from "@bosca/bml";

/** Fits native navigation links around the current page and a component-owned overflow disclosure. */
export function overflowTabs(ctx: ClientContext) {
  const row = ctx.ref<HTMLElement>("row");
  const more = ctx.ref<HTMLButtonElement>("more");
  const menu = ctx.ref<HTMLElement>("menu");
  if (!row || !more || !menu) return;
  let opened = false;
  let disposed = false;
  let signature = "";
  let focusedEntry: HTMLAnchorElement | null = null;
  const links = () => [...row.querySelectorAll<HTMLAnchorElement>(":scope > a")];
  const entries = () => [...menu.querySelectorAll<HTMLAnchorElement>(":scope > a")];
  menu.id = ctx.scoped("overflow");
  menu.setAttribute("popover", "manual");
  more.setAttribute("aria-controls", menu.id);
  const position = () => {
    if (!opened || !ctx.root.isConnected) return;
    const viewportWidth = document.documentElement.clientWidth;
    menu.style.maxWidth = `${Math.max(0, viewportWidth - 24)}px`;
    const anchor = more.getBoundingClientRect();
    if (anchor.bottom < 0 || anchor.top > innerHeight || anchor.right < 0 || anchor.left > viewportWidth) {
      close(false);
      return;
    }
    const bounds = menu.getBoundingClientRect();
    const below = innerHeight - anchor.bottom - 12;
    const top = below >= bounds.height + 4 ? anchor.bottom + 4 : anchor.top - bounds.height - 4;
    menu.style.left = `${Math.max(12, Math.min(anchor.right - bounds.width, viewportWidth - bounds.width - 12))}px`;
    menu.style.top = `${Math.max(12, Math.min(top, innerHeight - bounds.height - 12))}px`;
  };
  const close = (restoreFocus: boolean) => {
    opened = false;
    focusedEntry = null;
    if (menu.matches(":popover-open")) menu.hidePopover();
    menu.hidden = true;
    more.setAttribute("aria-expanded", "false");
    if (restoreFocus && more.isConnected && !more.hidden) more.focus({ preventScroll: true });
  };
  const show = () => {
    if (disposed || more.hidden || !entries().length) return;
    opened = true;
    menu.hidden = false;
    more.setAttribute("aria-expanded", "true");
    if (!menu.matches(":popover-open")) menu.showPopover();
    position();
  };
  const focusLink = (link: HTMLAnchorElement | undefined) => {
    link?.focus({ preventScroll: true });
    if (link && menu.contains(link)) link.scrollIntoView({ block: "nearest" });
  };
  const layout = () => {
    if (disposed || !ctx.root.isConnected) return;
    ctx.root.setAttribute("data-tabs-enhanced", "");
    menu.setAttribute("aria-label", `${ctx.root.getAttribute("aria-label") ?? ""} — More`);
    const items = links();
    const activeElement = document.activeElement;
    const focus = focusedEntry?.isConnected ? focusedEntry
      : activeElement instanceof HTMLAnchorElement && (row.contains(activeElement) || menu.contains(activeElement)) ? activeElement : null;
    const focusHref = focus?.href;
    const focusOnMore = activeElement === more;
    items.forEach(link => { link.hidden = false; link.style.maxWidth = ""; });
    more.hidden = false;
    const gap = Number.parseFloat(getComputedStyle(row).columnGap) || 0;
    const widths = items.map(link => link.getBoundingClientRect().width);
    const available = row.getBoundingClientRect().width;
    const visible = new Set<number>();
    if (widths.reduce((sum, width) => sum + width + gap, -gap) <= available) {
      items.forEach((_, index) => visible.add(index));
    } else {
      let remaining = Math.max(0, available - more.getBoundingClientRect().width - gap);
      const active = items.findIndex(link => link.getAttribute("aria-current") === "page");
      if (active >= 0) {
        visible.add(active);
        items[active].style.maxWidth = `${remaining}px`;
        remaining -= Math.min(widths[active], remaining) + gap;
      }
      for (let index = 0; index < items.length; index++) {
        if (index === active) continue;
        if (widths[index] > remaining) break;
        visible.add(index);
        remaining -= widths[index] + gap;
      }
    }
    items.forEach((link, index) => { link.hidden = !visible.has(index); });
    const hidden = items.filter(link => link.hidden);
    const nextSignature = hidden.map(link => link.outerHTML).join("");
    if (signature !== nextSignature) {
      signature = nextSignature;
      menu.replaceChildren(...hidden.map(link => {
        const entry = link.cloneNode(true) as HTMLAnchorElement;
        entry.hidden = false;
        entry.removeAttribute("id");
        return entry;
      }));
    }
    more.hidden = !hidden.length;
    if (more.hidden) close(false);
    else if (opened) show();
    if (focusHref) {
      const target = [...entries(), ...items.filter(link => !link.hidden)].find(link => link.href === focusHref);
      if (target && (opened || row.contains(target))) {
        if (target !== document.activeElement) focusLink(target);
      } else if (focus?.hidden && !more.hidden) more.focus({ preventScroll: true });
    } else if (focusOnMore && more.hidden) {
      (items.find(link => link.getAttribute("aria-current") === "page") ?? items[0])?.focus({ preventScroll: true });
    }
  };
  ctx.on(more, "click", () => {
    if (opened) close(false);
    else { show(); focusLink(entries()[0]); }
  });
  ctx.on<FocusEvent>(menu, "focusin", event => {
    if (event.target instanceof HTMLAnchorElement) focusedEntry = event.target;
  });
  ctx.on<FocusEvent>(menu, "focusout", event => {
    if (event.relatedTarget === more) {
      focusedEntry = null;
      return;
    }
    if (event.relatedTarget instanceof Node && menu.contains(event.relatedTarget)) return;
    queueMicrotask(() => {
      if (!menu.contains(document.activeElement)) {
        focusedEntry = null;
        if (opened && document.activeElement !== more) close(false);
      }
    });
  });
  ctx.on<MouseEvent>(ctx.root, "click", event => {
    const link = event.target instanceof Element ? event.target.closest("a") : null;
    if (link && (row.contains(link) || menu.contains(link))) close(menu.contains(link));
  });
  ctx.on<KeyboardEvent>(ctx.root, "keydown", event => {
    if (event.key === "Escape" && opened) {
      event.preventDefault();
      event.stopPropagation();
      close(true);
      return;
    }
    if (event.target !== more && !menu.contains(event.target as Node)) return;
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    show();
    const items = entries();
    const index = items.indexOf(document.activeElement as HTMLAnchorElement);
    const next = event.key === "Home" ? 0 : event.key === "End" ? items.length - 1
      : event.key === "ArrowDown" ? (index + 1) % items.length
      : index < 0 ? items.length - 1 : (index - 1 + items.length) % items.length;
    focusLink(items[next]);
  });
  ctx.on<PointerEvent>(document, "pointerdown", event => {
    if (event.target instanceof Node && !ctx.root.contains(event.target)) close(false);
  });
  ctx.on(window, "resize", layout);
  ctx.on(window, "scroll", position, true);
  ctx.on<CustomEvent<IslandUpdate>>(document, "bml:update", event => {
    if (event.detail.target.contains(ctx.root)) layout();
  });
  ctx.on(document.fonts, "loadingdone", layout);
  void document.fonts.ready.then(() => { if (!disposed) layout(); });
  const observer = new ResizeObserver(layout);
  observer.observe(row);
  observer.observe(menu);
  ctx.onUpdate(layout);
  ctx.onUnmount(() => {
    disposed = true;
    observer.disconnect();
    close(false);
  });
  layout();
}
