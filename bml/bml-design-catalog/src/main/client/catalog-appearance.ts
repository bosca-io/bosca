import type { ClientContext } from "@bosca/bml";

const preference = "bosca_catalog_appearance";

/** Keep native appearance state consistent with the cookie used for the next document render. */
export function rememberAppearance(ctx: ClientContext) {
  const control = () => ctx.root.querySelector<HTMLInputElement>("[data-catalog-light]");
  const restore = () => {
    const saved = document.cookie.split(";").map(cookie => cookie.trim())
      .find(cookie => cookie.startsWith(`${preference}=`))?.slice(preference.length + 1);
    const input = control();
    if (input) input.checked = saved === "light";
  };
  ctx.on<Event>(ctx.root, "change", event => {
    const input = control();
    if (event.target !== input || !input) return;
    document.cookie = `${preference}=${input.checked ? "light" : "dark"}; Path=/; Max-Age=31536000; SameSite=Lax${location.protocol === "https:" ? "; Secure" : ""}`;
  });
  ctx.on(window, "pageshow", restore);
  ctx.onUpdate(restore);
  restore();
  ctx.root.setAttribute("data-catalog-appearance-ready", "");
  ctx.onUnmount(() => ctx.root.removeAttribute("data-catalog-appearance-ready"));
}
