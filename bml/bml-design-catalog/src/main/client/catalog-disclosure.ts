import type { ClientContext } from "@bosca/bml";

/** Retain a native example disclosure's state while its server-owned controls are replaced. */
export function retainDisclosure(ctx: ClientContext) {
  const disclosure = () => ctx.root.querySelector<HTMLDetailsElement>("[data-example-states]");
  let open = disclosure()?.open ?? false;
  ctx.on<Event>(ctx.root, "toggle", event => {
    if (event.target === disclosure()) open = disclosure()?.open ?? false;
  }, true);
  ctx.onUpdate(() => {
    const details = disclosure();
    if (details) details.open = open;
  });
}
