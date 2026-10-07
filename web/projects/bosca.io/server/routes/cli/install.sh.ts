/**
 * The CLI installer entry point, `curl -fsSL https://bosca.io/cli/install.sh | sh`.
 *
 * Redirects to the installer script published with the project's releases
 * (NUXT_CLI_INSTALL_SCRIPT_URL), so the short URL always serves the current
 * installer. `curl -L` follows the redirect.
 */
export default defineEventHandler((e) => {
  return sendRedirect(e, useRuntimeConfig(e).cliInstallScriptUrl, 302)
})
