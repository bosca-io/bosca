package bosca.experimentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.experimentation.model.AnalysisReport
import bosca.serialization.UUID

@Repository
interface AnalysisReportRepository {

    @Query("select * from experimentation.analysis_reports where experiment_id = :experimentId order by created desc, id desc")
    suspend fun getByExperimentId(experimentId: UUID): List<AnalysisReport>

    @Query("""
        select * from experimentation.analysis_reports
        where experiment_id = :experimentId
        order by created desc, id desc
        limit :limit offset :offset
    """)
    suspend fun getByExperimentId(experimentId: UUID, offset: Long, limit: Int): List<AnalysisReport>

    /**
     * Returns the newest report produced for the exact current analysis definition.
     * Filtering before `limit 1` prevents a later-finishing stale analysis from
     * shadowing an already persisted current report.
     */
    @Query("""
        select * from experimentation.analysis_reports
        where experiment_id = :experimentId
          and jsonb_typeof(details -> 'controlVariationKey') = 'string'
          and details ->> 'controlVariationKey' = :controlVariationKey
          and jsonb_typeof(details -> 'experimentRevision') = 'number'
          and details ->> 'experimentRevision' = cast(:experimentRevision as text)
        order by created desc, id desc
        limit 1
    """)
    suspend fun getLatestForRevision(
        experimentId: UUID,
        controlVariationKey: String,
        experimentRevision: Long,
    ): List<AnalysisReport>

    @Query("""
        select reports.* from experimentation.analysis_reports reports
        where reports.experiment_id = :experimentId
        order by case when reports.id = (
            select current_report.id
            from experimentation.analysis_reports current_report
            where current_report.experiment_id = :experimentId
              and jsonb_typeof(current_report.details -> 'controlVariationKey') = 'string'
              and current_report.details ->> 'controlVariationKey' = :controlVariationKey
              and jsonb_typeof(current_report.details -> 'experimentRevision') = 'number'
              and current_report.details ->> 'experimentRevision' = cast(:experimentRevision as text)
            order by current_report.created desc, current_report.id desc
            limit 1
        ) then 0 else 1 end,
        reports.created desc,
        reports.id desc
        limit :limit offset :offset
    """)
    suspend fun getByExperimentIdForRevision(
        experimentId: UUID,
        controlVariationKey: String,
        experimentRevision: Long,
        offset: Long,
        limit: Int,
    ): List<AnalysisReport>

    @Query("""
        insert into experimentation.analysis_reports (experiment_id, summary, recommendation, details, confidence, ai_insights)
        values (:experimentId, :summary, :recommendation, :details::jsonb, :confidence, :aiInsights::jsonb)
        returning *
    """)
    suspend fun add(report: AnalysisReport): AnalysisReport
}
