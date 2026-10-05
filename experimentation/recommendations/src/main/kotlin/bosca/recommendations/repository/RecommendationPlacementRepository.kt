package bosca.recommendations.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.recommendations.model.RecommendationPlacement
import bosca.serialization.UUID

/**
 * Persists and retrieves recommendation placements, which represent named display slots
 * (e.g., homepage hero, sidebar widget) where recommendations are surfaced to users.
 * Each placement carries a slug for URL-friendly lookups, a maximum item cap, and
 * optional JSON configuration that controls rendering behavior.
 */
@Repository
interface RecommendationPlacementRepository {

    @Query("select * from recommendations.placements order by name")
    suspend fun getAll(): List<RecommendationPlacement>

    @Query("select * from recommendations.placements where id = :id")
    suspend fun getById(id: UUID): RecommendationPlacement?

    @Query("select * from recommendations.placements where slug = :slug")
    suspend fun getBySlug(slug: String): RecommendationPlacement?

    @Query("""
        insert into recommendations.placements (name, description, slug, max_items, configuration)
        values (:name, :description, :slug, :maxItems, :configuration::jsonb)
        returning *
    """)
    suspend fun add(placement: RecommendationPlacement): RecommendationPlacement

    @Query("""
        update recommendations.placements
        set name = :name, description = :description, slug = :slug,
            max_items = :maxItems, configuration = :configuration::jsonb, modified = now()
        where id = :id
        returning *
    """)
    suspend fun update(placement: RecommendationPlacement): RecommendationPlacement

    @Query("delete from recommendations.placements where id = :id")
    suspend fun deleteById(id: UUID)
}
