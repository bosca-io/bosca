interface ArtifactVersions {
  versions: Array<{ version: string }>
}

interface GitHubRelease {
  tag_name: string
  draft: boolean
  prerelease: boolean
}

/** Release checks use the same repository as the website's installer. */
export default defineEventHandler(async (e) => {
  const installer = new URL(useRuntimeConfig(e).cliInstallScriptUrl)
  const artifactsUrl = cliArtifactsRepository(installer)
  const stable = /^[0-9]+\.[0-9]+\.[0-9]+$/
  setHeader(e, 'Cache-Control', 'no-store')
  if (artifactsUrl) {
    const listing = await $fetch<ArtifactVersions>(cliArtifactsListingUrl(artifactsUrl))
    return {
      version: listing.versions.find(item => stable.test(item.version))?.version ?? null,
      artifactsUrl
    }
  }

  const path = installer.pathname.match(/^\/([^/]+)\/([^/]+)\/.+\/cli\/install\.sh$/)
  if (installer.hostname !== 'raw.githubusercontent.com' || !path) {
    throw createError({ statusCode: 502, statusMessage: 'No release repository can be resolved from the configured installer URL.' })
  }
  const repository = path[1] + '/' + path[2]
  const listing = await $fetch<GitHubRelease[]>('https://api.github.com/repos/' + repository + '/releases?per_page=100')
  return {
    version: listing.find(item => !item.draft && !item.prerelease
      && item.tag_name.startsWith('cli-v') && stable.test(item.tag_name.slice(5)))?.tag_name.slice(5) ?? null,
    repository
  }
})
