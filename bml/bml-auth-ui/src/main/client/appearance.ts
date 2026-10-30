export type Appearance = "light" | "dark";
const preference = "git_ui_appearance";

/** Read the same validated preference used by the server's initial document. */
export function savedAppearance(): Appearance {
  const value = document.cookie.split(";").map(cookie => cookie.trim())
    .find(cookie => cookie.startsWith(`${preference}=`))?.slice(preference.length + 1);
  return value === "light" ? "light" : "dark";
}

/** Apply consumer CSS immediately and retain the preference for subsequent server renders. */
export function setAppearance(value: Appearance, remember = true) {
  if (remember) document.cookie = `${preference}=${value}; Path=/; Max-Age=31536000; SameSite=Lax${location.protocol === "https:" ? "; Secure" : ""}`;
  document.documentElement.dataset.theme = value;
  document.body.dataset.theme = value;
}
