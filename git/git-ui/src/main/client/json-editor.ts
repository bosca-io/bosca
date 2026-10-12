import type { ClientContext } from "@bosca/bml";
import type { Content } from "vanilla-jsoneditor";
import { createJSONEditor, Mode, toTextContent } from "vanilla-jsoneditor";

/** Mounts the structured editor engine used by Studio and keeps its native form value current. */
export function mountJsonEditor(ctx: ClientContext) {
  const target = ctx.ref<HTMLElement>("editor");
  const field = ctx.ref<HTMLInputElement>("value");
  if (!target || !field) return;
  const editor = createJSONEditor({
    target,
    props: {
      content: { text: field.value || "{}" },
      mode: Mode.tree,
      onChange: (content: Content) => {
        field.value = toTextContent(content).text;
        field.dispatchEvent(new Event("input", { bubbles: true }));
      },
    },
  });
  ctx.onUnmount(() => {
    void editor.destroy();
  });
}
