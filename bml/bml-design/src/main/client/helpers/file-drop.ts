import type { ClientContext } from "@bosca/bml";

/** Selects local files; the caller owns validation, storage and transport. */
export function fileDrop(ctx: ClientContext): void {
  const input = ctx.ref<HTMLInputElement>("files");
  if (!input) return;
  const disabled = () => ctx.root.hasAttribute("data-disabled");
  const sync = () => {
    input.disabled = disabled();
    ctx.root.setAttribute("aria-disabled", String(input.disabled));
    if (input.disabled) ctx.root.removeAttribute("data-dragging");
  };
  const select = (files: FileList | null) => {
    const selected = Array.from(files ?? []);
    input.value = "";
    if (disabled() || !selected.length) return;
    ctx.root.dispatchEvent(new CustomEvent<File[]>("filesselected", {
      bubbles: true, detail: input.multiple ? selected : selected.slice(0, 1),
    }));
  };
  const isFileDrag = (event: DragEvent) => Array.from(event.dataTransfer?.types ?? []).includes("Files");
  ctx.on(input, "change", () => select(input.files));
  ctx.on<DragEvent>(ctx.root, "dragover", event => {
    if (!isFileDrag(event)) return;
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = disabled() ? "none" : "copy";
    if (!disabled()) ctx.root.setAttribute("data-dragging", "");
  });
  ctx.on<DragEvent>(ctx.root, "dragleave", event => {
    if (event.relatedTarget instanceof Node && ctx.root.contains(event.relatedTarget)) return;
    ctx.root.removeAttribute("data-dragging");
  });
  ctx.on<DragEvent>(ctx.root, "drop", event => {
    ctx.root.removeAttribute("data-dragging");
    if (!isFileDrag(event)) return;
    event.preventDefault();
    select(event.dataTransfer?.files ?? null);
  });
  const observer = new MutationObserver(sync);
  observer.observe(ctx.root, { attributes: true, attributeFilter: ["data-disabled"] });
  ctx.onUnmount(() => observer.disconnect());
  ctx.onUpdate(sync);
  sync();
}
