package bosca.installer.service

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion

/**
 * Applies a specific type of installation logic for a package version.
 *
 * Different installers handle different aspects of package installation (e.g., database seeding,
 * file deployment, configuration updates). Each installer is identified by name through the DI
 * system and declares its own [version] for idempotency tracking.
 */
interface PackageInstaller {

    /** The version of this installer, used to track whether it has already been applied. */
    val version: String

    /**
     * Performs the installation for the given package [version] within the context
     * of the specified [installation].
     *
     * @param installation the package definition being installed
     * @param version the specific version of the package to install
     */
    suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion)
}