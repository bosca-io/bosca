import type { ClientContext } from "@bosca/bml";

const slugify = (value: string) =>
  value
    .toLowerCase()
    .replace(/[^a-z0-9-]/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "");

/** Suggests a slug from the name while preserving a slug the user edited. */
export function suggestSlug(ctx: ClientContext) {
  const form = ctx.root.querySelector<HTMLFormElement>("form");
  const name = form?.elements.namedItem("name");
  const slug = form?.elements.namedItem("slug");
  if (
    !(name instanceof HTMLInputElement) ||
    !(slug instanceof HTMLInputElement)
  )
    return;
  let suggestion = slugify(name.value);
  ctx.on<Event>(ctx.root, "input", (event) => {
    const source = event.target;
    if (!(source instanceof HTMLInputElement) || source.name !== "name") return;
    const target = source.form?.elements.namedItem("slug");
    if (!(target instanceof HTMLInputElement)) return;
    const next = slugify(source.value);
    if (!target.value || target.value === suggestion) target.value = next;
    suggestion = next;
  });
}
