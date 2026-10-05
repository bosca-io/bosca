package bosca.installer.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.installer.model.PackageInstallationHistory

/**
 * Database repository for persisting and querying package installation history records.
 *
 * Each record tracks when a specific package version was installed, enabling the
 * [PackageInstallationService][bosca.installer.service.PackageInstallationService]
 * to skip already-applied installations.
 */
@Repository
interface PackageInstallationsRepository {

    /**
     * Retrieves all installation history records, ordered by creation date descending.
     *
     * @return a list of all [PackageInstallationHistory] entries
     */
    @Query("select * from package_installations order by created desc")
    suspend fun getHistory(): List<PackageInstallationHistory>

    /**
     * Inserts a new installation history record and returns the persisted entity
     * (including any server-generated fields like the ID).
     *
     * @param history the installation history record to persist
     * @return the persisted [PackageInstallationHistory] with generated fields populated
     */
    @Query("insert into package_installations (key, version, created) values (:key, :version, :created) returning *")
    suspend fun addHistory(history: PackageInstallationHistory): PackageInstallationHistory
}