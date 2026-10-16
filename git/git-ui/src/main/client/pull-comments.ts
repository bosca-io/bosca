import type { ClientContext } from "@bosca/bml";

/** Keep per-line drafts and return focus to the line after its composer closes. */
export function pullComments(ctx: ClientContext) {
  const drafts = new Map<string, string>();
  let field: HTMLTextAreaElement | null = null;
  let anchor = "";
  let sequence = "";
  const sync = () => {
    const next = ctx.ref<HTMLTextAreaElement>("commentBody");
    const form = ctx.ref<HTMLFormElement>("commentForm");
    if (!next || !form) {
      if (field) {
        drafts.delete(anchor);
        const row = [...ctx.root.querySelectorAll<HTMLElement>("[data-comment-anchor]")]
          .find((element) => element.dataset.commentAnchor === anchor);
        const target = row instanceof HTMLButtonElement ? row : row?.querySelector<HTMLButtonElement>("button");
        target?.focus({ preventScroll: true });
      }
      field = null;
      anchor = "";
      sequence = "";
      return;
    }
    const nextAnchor = form.dataset.commentAnchor ?? "";
    const opened = sequence !== form.dataset.openSequence;
    if (next !== field) {
      const draft = drafts.get(nextAnchor);
      if (draft !== undefined) next.value = draft;
      next.dispatchEvent(new Event("input", { bubbles: true }));
    }
    if (opened || form.hasAttribute("data-comment-failed")) {
      next.focus({ preventScroll: true });
      form.scrollIntoView({ block: "nearest" });
    }
    field = next;
    anchor = nextAnchor;
    sequence = form.dataset.openSequence ?? "";
  };
  ctx.on<InputEvent>(ctx.root, "input", (event) => {
    if (field && event.target === field) drafts.set(anchor, field.value);
  });
  ctx.onUpdate(sync);
  ctx.onUnmount(() => drafts.clear());
  sync();
}
