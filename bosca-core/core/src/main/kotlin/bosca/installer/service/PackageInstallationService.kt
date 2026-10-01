package bosca.installer.service

import bosca.installer.model.PackageInstallationHistory
import bosca.installer.model.PackageInstallations
import bosca.service.Service

/**
 * Orchestrates the installation of packages defined in the system's package configuration.
 *
 * A package represents a set of versioned resources (e.g., database seeds, configuration data)
 * that are applied to the system via registered [PackageInstaller] implementations.
 * This service tracks installation history to avoid re-applying already-installed versions.
 */
interface PackageInstallationService : Service {

    /**
     * Installs all packages whose keys match the given set, applying any versions
     * that have not yet been installed.
     *
     * @param keys the set of package keys to install
     */
    suspend fun install(keys: Set<String>)

    /**
     * Installs a specific version of a package identified by [key], running all
     * registered installers for that version.
     *
     * @param key the package key
     * @param version the version to install
     * @return `true` if the installation was performed, `false` if it was already installed
     */
    suspend fun install(key: String, version: String): Boolean

    /**
     * Installs a specific version of a package using a single named installer,
     * skipping the installation if the given installer version has already been applied.
     *
     * @param key the package key
     * @param version the version to install
     * @param installerName the name of the specific installer to use
     * @param installerVersion the version of the installer (used for idempotency tracking)
     * @return `true` if the installation was performed, `false` if it was already applied
     */
    suspend fun install(key: String, version: String, installerName: String, installerVersion: String): Boolean

    /**
     * Returns the complete installation history, ordered by creation date descending.
     *
     * @return a list of [PackageInstallationHistory] records
     */
    suspend fun getHistory(): List<PackageInstallationHistory>
}