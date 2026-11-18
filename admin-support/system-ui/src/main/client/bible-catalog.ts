import type { ClientContext } from "@bosca/bml";

/** Starts the tag's declarative catalog/target actions after rendering, without blocking the page response. */
export function mountBibleCatalog(ctx: ClientContext) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      for (const name of ["catalogForm", "targetsForm"]) {
        const form = ctx.ref(name);
        if (form instanceof HTMLFormElement && form.hasAttribute("data-autoload") && !form.hasAttribute("aria-busy")) {
          form.removeAttribute("data-autoload");
          form.requestSubmit();
          break;
        }
      }
    }, 0);
  };
  ctx.onUpdate(schedule);
  ctx.onUnmount(() => clearTimeout(timer));
  schedule();
}
