package bosca.kubernetes.repository

import bosca.db.annotation.ColumnName
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Persists configured helm chart repositories. The cached
 * `indexYaml` field stores the raw `index.yaml` content from the
 * upstream repo; readers re-parse on demand so a parser-side change
 * never invalidates the on-disk cache.
 *
 * The table is shared between both binaries — `bosca-server` is the
 * write side via the helm-repo mutation flow, `kubernetes-controller`
 * is the read side via its catalog routes (and the refresh loop, when
 * that lands).
 */
@Repository
interface HelmRepoRepository {

    @Query("select * from kubernetes.helm_repo order by name")
    suspend fun list(): List<HelmRepo>

    @Query("select * from kubernetes.helm_repo where name = :name")
    suspend fun getByName(name: String): HelmRepo?

    @Query(
        """
        insert into kubernetes.helm_repo (name, url, type, index_yaml, last_index_at)
        values (:name, :url, :type, :indexYaml, case when :indexYaml is null then null else now() end)
        on conflict (name) do update set
            url = excluded.url,
            type = excluded.type,
            index_yaml = excluded.index_yaml,
            last_index_at = case when excluded.index_yaml is null then kubernetes.helm_repo.last_index_at else now() end,
            modified_at = now()
        returning *
        """,
    )
    suspend fun upsert(
        name: String,
        url: String,
        type: String,
        indexYaml: String?,
    ): HelmRepo

    @Query(
        """
        update kubernetes.helm_repo
        set index_yaml = :indexYaml,
            last_index_at = now(),
            modified_at = now()
        where name = :name
        returning *
        """,
    )
    suspend fun updateIndex(name: String, indexYaml: String?): HelmRepo?

    @Query("delete from kubernetes.helm_repo where name = :name")
    suspend fun delete(name: String)
}

/** Configured Helm repository persisted in `kubernetes.helm_repo`. */
@Serializable
data class HelmRepo(
    val name: String,
    val url: String,
    val type: String,
    @ColumnName("last_index_at")
    @Contextual
    val lastIndexAt: OffsetDateTime? = null,
    @ColumnName("index_yaml")
    val indexYaml: String? = null,
)
