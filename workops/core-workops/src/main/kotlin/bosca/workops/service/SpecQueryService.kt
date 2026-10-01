package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.spec.Spec

data class SpecSearchResult(
    val rows: List<Spec>,
    val freeTextTerms: List<String>,
)

interface SpecQueryService : Service {
    suspend fun validate(bqlSource: String): List<bosca.workops.model.bql.BqlError>

    suspend fun search(
        bqlSource: String,
        actingProfileId: UUID?,
        offset: Long = 0,
        limit: Int = 50,
    ): SpecSearchResult
}
