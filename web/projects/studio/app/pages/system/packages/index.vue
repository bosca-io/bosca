<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

// `packages` is a root query/mutation namespace (sibling of `postgresAdmin`,
// `natsAdmin`, etc.) — it is NOT nested under a `system` field. Wrapping it in
// `system { ... }` makes every operation fail schema validation.
const packagesGql = gql`
  query GetPackages {
    packages {
      all {
        key
        name
        versions {
          version
          installers { name version }
          installed { name version }
        }
      }
      history {
        id
        key
        version
        created
      }
    }
  }
`

const installPackageGql = gql`
  mutation InstallPackage($key: String!, $version: String!) {
    packages { install(key: $key, version: $version) }
  }
`

const installInstallerGql = gql`
  mutation InstallInstaller($key: String!, $version: String!, $installerName: String!, $installerVersion: String!) {
    packages { installInstaller(key: $key, version: $version, installerName: $installerName, installerVersion: $installerVersion) }
  }
`

interface Installer {
  name: string
  version: string
}

interface PackageVersion {
  version: string
  /** Installers available to install for this version. */
  installers: Installer[]
  /** Installers currently installed for this version (authoritative). */
  installed: Installer[]
}

interface Package {
  key: string
  name: string
  versions: PackageVersion[]
}

interface HistoryEntry {
  id: string
  key: string
  version: string
  created: string
}

const { data, status, refresh } = useAsyncQuery<{
  packages: { all: Package[]; history: HistoryEntry[] }
}>('packages', packagesGql)

const packages = computed(() => data.value?.packages?.all ?? [])
const history = computed(() => data.value?.packages?.history ?? [])
const isLoading = computed(() => status.value === 'pending')

const activeTab = ref('Packages')

// Install state is derived from each version's authoritative `installed` array
// rather than the history log: history records that an install was attempted,
// whereas `installed` reflects what is actually present now.
function isInstallerInstalled(version: PackageVersion, installer: Installer): boolean {
  return version.installed.some(i => i.name === installer.name && i.version === installer.version)
}

/** A version counts as installed when every available installer is present. */
function isVersionInstalled(version: PackageVersion): boolean {
  return version.installers.length > 0 && version.installers.every(i => isInstallerInstalled(version, i))
}

function installedCount(pkg: Package): number {
  return pkg.versions.filter(isVersionInstalled).length
}

function packageBadgeColor(pkg: Package): string {
  const installed = installedCount(pkg)
  if (installed === 0) return 'var(--fg-3)'
  return installed === pkg.versions.length ? 'var(--ok)' : 'var(--warn)'
}

const totalVersions = computed(() =>
  packages.value.reduce((sum, p) => sum + p.versions.length, 0),
)
const installedVersions = computed(() =>
  packages.value.reduce((sum, p) => sum + installedCount(p), 0),
)

const subtitle = computed(() => {
  const count = `${packages.value.length} package${packages.value.length === 1 ? '' : 's'}`
  if (totalVersions.value === 0) return count
  return `${count} · ${installedVersions.value}/${totalVersions.value} versions installed`
})

// Tracks in-flight installs by a composite key so individual buttons can show
// their own progress without disabling the rest of the page.
const installingKeys = reactive<Set<string>>(new Set())

function versionKey(pkg: Package, version: PackageVersion): string {
  return `${pkg.key}@${version.version}`
}

function installerKey(pkg: Package, version: PackageVersion, installer: Installer): string {
  return `${pkg.key}@${version.version}:${installer.name}`
}

async function installVersion(pkg: Package, version: PackageVersion) {
  const key = versionKey(pkg, version)
  if (installingKeys.has(key)) return
  installingKeys.add(key)
  try {
    const result = await gqlMutation<{ packages: { install: boolean } }>(
      installPackageGql,
      { key: pkg.key, version: version.version },
    )
    if (result?.packages?.install) {
      toast.success(`Installed ${pkg.name} v${version.version}`)
    } else {
      toast.warn(`${pkg.name} v${version.version} was already installed`)
    }
    await refresh()
  } catch (e) {
    toast.error((e as Error)?.message || `Failed to install ${pkg.name} v${version.version}`)
  } finally {
    installingKeys.delete(key)
  }
}

async function installSingleInstaller(pkg: Package, version: PackageVersion, installer: Installer) {
  const key = installerKey(pkg, version, installer)
  if (installingKeys.has(key)) return
  installingKeys.add(key)
  try {
    const result = await gqlMutation<{ packages: { installInstaller: boolean } }>(
      installInstallerGql,
      {
        key: pkg.key,
        version: version.version,
        installerName: installer.name,
        installerVersion: installer.version,
      },
    )
    if (result?.packages?.installInstaller) {
      toast.success(`Installed ${installer.name} v${installer.version}`)
    } else {
      toast.warn(`${installer.name} v${installer.version} was already installed`)
    }
    await refresh()
  } catch (e) {
    toast.error((e as Error)?.message || `Failed to install ${installer.name}`)
  } finally {
    installingKeys.delete(key)
  }
}

function formatDate(d: string | null | undefined): string {
  if (!d) return '—'
  const date = new Date(d)
  if (Number.isNaN(date.getTime())) return d
  return date.toLocaleString(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Packages')"
        title="Packages"
        :subtitle="subtitle"
      >
        <template #actions>
          <Button size="sm" icon="refresh" @click="refresh()">Refresh</Button>
        </template>
      </PageHeader>
    </template>

    <Tabs
      v-model="activeTab"
      :tabs="['Packages', 'History']"
      :accent="accent"
    />

    <!-- Packages Tab -->
    <div v-if="activeTab === 'Packages'" class="tab-content">
      <div v-if="isLoading && packages.length === 0" class="empty-state">Loading…</div>
      <div v-else-if="packages.length === 0" class="empty-state">No packages available.</div>

      <SectionCard
        v-for="pkg in packages"
        :key="pkg.key"
        :title="pkg.name"
        class="pkg-card">
        <template #right>
          <div class="pkg-meta">
            <span class="pkg-key mono">{{ pkg.key }}</span>
            <Badge :color="packageBadgeColor(pkg)">
              {{ installedCount(pkg) }} / {{ pkg.versions.length }} installed
            </Badge>
          </div>
        </template>

        <div v-for="ver in pkg.versions" :key="ver.version" class="version-block">
          <div class="version-header">
            <Badge :color="isVersionInstalled(ver) ? 'var(--ok)' : 'var(--fg-3)'">
              v{{ ver.version }}
            </Badge>
            <span v-if="isVersionInstalled(ver)" class="installed-label">All installers installed</span>
            <span style="flex: 1" />
            <Button
              v-if="!isVersionInstalled(ver) && ver.installers.length > 0"
              size="sm"
              primary
              :accent="accent"
              :disabled="installingKeys.has(versionKey(pkg, ver))"
              @click="installVersion(pkg, ver)"
            >
              {{ installingKeys.has(versionKey(pkg, ver)) ? 'Installing…' : 'Install All' }}
            </Button>
          </div>

          <div v-if="ver.installers.length > 0" class="installers-table">
            <div v-for="inst in ver.installers" :key="inst.name" class="installer-row">
              <span class="installer-name mono">{{ inst.name }}</span>
              <span class="installer-version mono">{{ inst.version }}</span>
              <span style="flex: 1" />
              <Badge v-if="isInstallerInstalled(ver, inst)" color="var(--ok)">Installed</Badge>
              <Button
                v-else
                size="sm"
                :disabled="installingKeys.has(installerKey(pkg, ver, inst))"
                @click="installSingleInstaller(pkg, ver, inst)"
              >
                {{ installingKeys.has(installerKey(pkg, ver, inst)) ? 'Installing…' : 'Install' }}
              </Button>
            </div>
          </div>
          <div v-else class="no-installers">No installers in this version.</div>
        </div>
      </SectionCard>
    </div>

    <!-- History Tab -->
    <div v-if="activeTab === 'History'" class="tab-content">
      <SectionCard title="Installation History">
        <div v-if="isLoading && history.length === 0" class="empty-state">Loading…</div>
        <div v-else-if="history.length === 0" class="empty-state">No installation history.</div>
        <div v-else>
          <div v-for="entry in history" :key="entry.id" class="history-row">
            <Icon name="package" :size="16" color="var(--fg-3)" />
            <div class="history-info">
              <span class="history-key mono">{{ entry.key }}</span>
              <span class="history-version">v{{ entry.version }}</span>
            </div>
            <span class="history-date mono">{{ formatDate(entry.created) }}</span>
          </div>
        </div>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.tab-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 14px;
}

.empty-state {
  padding: 48px;
  text-align: center;
  color: var(--fg-3);
  font-size: 13px;
}

.pkg-card {
  margin-bottom: 0;
}

.pkg-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.pkg-key {
  font-size: 11px;
  color: var(--fg-4);
}

.version-block {
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}

.version-block:last-child {
  border-bottom: none;
}

.version-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.installed-label {
  font-size: 11px;
  color: var(--ok);
  font-weight: 500;
}

.installers-table {
  display: flex;
  flex-direction: column;
  gap: 1px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.installer-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: var(--bg-0);
}

.installer-name {
  font-size: 12px;
  color: var(--fg-1);
}

.installer-version {
  font-size: 11px;
  color: var(--fg-3);
}

.no-installers {
  font-size: 12px;
  color: var(--fg-4);
  padding: 8px 0;
}

.history-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line);
}

.history-row:last-child {
  border-bottom: none;
}

.history-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.history-key {
  font-size: 12.5px;
  color: var(--fg-1);
  font-weight: 500;
}

.history-version {
  font-size: 11px;
  color: var(--fg-3);
}

.history-date {
  font-size: 11px;
  color: var(--fg-3);
}
</style>
