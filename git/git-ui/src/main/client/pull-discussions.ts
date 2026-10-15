import type { ClientContext } from "@bosca/bml";

/** Reveal and focus a linked discussion after the review island is ready. */
export function pullDiscussions(ctx: ClientContext) {
  let revealed = "";
  const reveal = () => {
    const hash = location.hash;
    if (!hash || hash === revealed) return;
    let id: string;
    try {
      id = decodeURIComponent(hash.slice(1));
    } catch {
      return;
    }
    const thread = document.getElementById(id);
    if (!thread?.hasAttribute("data-thread") || !ctx.root.contains(thread)) return;
    for (
      let parent = thread.parentElement;
      parent && ctx.root.contains(parent);
      parent = parent.parentElement
    ) {
      if (parent instanceof HTMLDetailsElement) parent.open = true;
      if (parent.matches(".diff-file[data-collapsed]"))
        parent.querySelector<HTMLElement>(".diff-file-toggle")?.click();
    }
    thread.scrollIntoView({ block: "center" });
    thread.focus({ preventScroll: true });
    revealed = hash;
  };
  ctx.on(window, "hashchange", () => {
    revealed = "";
    reveal();
  });
  ctx.onUpdate(reveal);
  reveal();
}
