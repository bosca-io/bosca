/**
 * The CLI installer entry point, `curl -fsSL https://bosca.io/cli/install.sh | sh`.
 *
 * Bosca raw installer URLs also select the package repository by default.
 * Other installer sources are served through a redirect.
 */
export default defineEventHandler(async (e) => {
  const source = new URL(useRuntimeConfig(e).cliInstallScriptUrl)
  const rawInstaller = source.pathname.match(/^(.*\/raw\/[^/]+)\/([^/]+)\/[^/]+\/install\.sh$/)
  if (!rawInstaller) {
    return sendRedirect(e, source.href, 302)
  }

  const script = await $fetch<string>(source.href, { responseType: 'text' })
  if (!script.includes('BOSCA_CLI_ARTIFACTS_URL')) {
    throw createError({
      statusCode: 502,
      statusMessage: 'The configured installer lacks Bosca Artifacts support. Publish the updated cli/install.sh.'
    })
  }

  const repository = source.origin + rawInstaller[1] + '/' + rawInstaller[2]
  const quotedRepository = "'" + repository.replaceAll("'", "'\\''") + "'"
  setHeader(e, 'Content-Type', 'text/x-shellscript; charset=utf-8')
  return `#!/bin/sh
if [ -z "\${BOSCA_CLI_ARTIFACTS_URL:-}" ]; then
  BOSCA_CLI_ARTIFACTS_URL=${quotedRepository}
  export BOSCA_CLI_ARTIFACTS_URL
fi
${script}`
})
