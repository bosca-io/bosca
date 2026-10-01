package bosca.content.healthcheck

import bosca.content.metadata.model.ContentHealthCheckItem
import bosca.db.connection
import bosca.serialization.UUID
import java.sql.ResultSet

/**
 * Repository that executes diagnostic SQL queries against the content database
 * to identify health issues such as broken relationships, workflow inconsistencies,
 * and content that requires editorial attention.
 */
class ContentHealthCheckRepository {

    /**
     * Finds published metadata items whose relationships reference metadata items
     * that are not fully publicly accessible. A related item is considered inaccessible
     * if it lacks the public or public_content visibility flags, or if it has supplementary
     * content but the public_supplementary flag is not set. Items without supplementary
     * content (e.g. YouTube videos) are not flagged for missing public_supplementary.
     */
    suspend fun findPublishedWithUnpublishedRelationships(offset: Int, limit: Int): List<ContentHealthCheckItem> {
        val sql = """
            select distinct m.id, m.name, m.workflow_state_id
            from metadata m
            inner join workflow_states mws on m.workflow_state_id = mws.id
            inner join metadata_relationships mr on m.id = mr.metadata1_id
            inner join metadata m2 on mr.metadata2_id = m2.id
            inner join workflow_states ws on m2.workflow_state_id = ws.id
            where m.deleted = false
              and m2.deleted = false
              and mws.type in ('published', 'advertised')
              and (ws.type not in ('published', 'advertised')
                or m2.public = false
                or m2.public_content = false
                or (m2.public_supplementary = false
                    and exists (select 1 from metadata_supplementary ms where ms.metadata_id = m2.id)))
            order by m.name
            limit ? offset ?
        """.trimIndent()
        return executeQuery(sql, limit, offset)
    }

    /**
     * Finds metadata items that have a pending published state with a valid date
     * in the past but have not yet transitioned to the published state.
     */
    suspend fun findScheduledButNotPublished(offset: Int, limit: Int): List<ContentHealthCheckItem> {
        val sql = """
            select m.id, m.name, m.workflow_state_id
            from metadata m
            inner join workflow_states ws on m.workflow_state_pending_id = ws.id
            where m.deleted = false
              and m.workflow_state_pending_id is not null
              and ws.type = 'published'
              and m.workflow_state_valid is not null
              and m.workflow_state_valid <= now()
              and m.workflow_state_id != m.workflow_state_pending_id
            order by m.workflow_state_valid
            limit ? offset ?
        """.trimIndent()
        return executeQuery(sql, limit, offset)
    }

    /**
     * Finds metadata items in a pending workflow state type that have not been
     * marked as ready for publishing by an editor.
     */
    suspend fun findPendingNotReady(offset: Int, limit: Int): List<ContentHealthCheckItem> {
        val sql = """
            select m.id, m.name, m.workflow_state_id
            from metadata m
            inner join workflow_states ws on m.workflow_state_id = ws.id
            where m.deleted = false
              and ws.type = 'pending'
              and m.ready is null
            order by m.modified desc
            limit ? offset ?
        """.trimIndent()
        return executeQuery(sql, limit, offset)
    }

    /**
     * Finds published guide metadata items that contain steps whose referenced
     * metadata is not in a published or advertised state.
     */
    suspend fun findGuidesWithUnpublishedSteps(offset: Int, limit: Int): List<ContentHealthCheckItem> {
        val sql = """
            select distinct m.id, m.name, m.workflow_state_id
            from metadata m
            inner join workflow_states mws on m.workflow_state_id = mws.id
            inner join guides g on m.id = g.metadata_id and m.version = g.version
            inner join guide_steps gs on g.metadata_id = gs.metadata_id and g.version = gs.version
            inner join metadata sm on gs.step_metadata_id = sm.id
            inner join workflow_states ws on sm.workflow_state_id = ws.id
            where m.deleted = false
              and sm.deleted = false
              and mws.type in ('published', 'advertised')
              and ws.type not in ('published', 'advertised')
            order by m.name
            limit ? offset ?
        """.trimIndent()
        return executeQuery(sql, limit, offset)
    }

    /**
     * Finds published collections that contain child metadata items not
     * in a published or advertised workflow state.
     */
    suspend fun findPublishedCollectionsWithUnpublishedMetadata(offset: Int, limit: Int): List<ContentHealthCheckItem> {
        val sql = """
            select distinct c.id, c.name, c.workflow_state_id
            from collections c
            inner join workflow_states cws on c.workflow_state_id = cws.id
            inner join collection_items ci on c.id = ci.collection_id
            inner join metadata m on ci.child_metadata_id = m.id
            inner join workflow_states ws on m.workflow_state_id = ws.id
            where c.deleted = false
              and m.deleted = false
              and cws.type in ('published', 'advertised')
              and ws.type not in ('published', 'advertised')
            order by c.name
            limit ? offset ?
        """.trimIndent()
        return executeQuery(sql, limit, offset)
    }

    /**
     * Finds metadata items whose current workflow state has a type of FAILURE,
     * indicating a job processing error that needs resolution.
     */
    suspend fun findFailedJobItems(offset: Int, limit: Int): List<ContentHealthCheckItem> {
        val sql = """
            select m.id, m.name, m.workflow_state_id
            from metadata m
            inner join workflow_states ws on m.workflow_state_id = ws.id
            where m.deleted = false
              and ws.type = 'failure'
            order by m.modified desc
            limit ? offset ?
        """.trimIndent()
        return executeQuery(sql, limit, offset)
    }

    /**
     * Counts the total number of soft-deleted metadata items that may need
     * permanent deletion or restoration.
     */
    suspend fun countDeletedItems(): Int {
        val sql = "select count(*) from metadata where deleted = true"
        val conn = connection()
        return conn.useReadOnlyStatement(sql) { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    /**
     * Finds metadata items that are not in a draft state and have no uploaded
     * content, indicating they may have been created but never had content assigned.
     * Excludes template definitions since they hold their content in dedicated
     * template tables rather than as uploaded content, and excludes external content
     * types (such as YouTube videos) that intentionally have no uploaded binary.
     */
    suspend fun findMissingContent(offset: Int, limit: Int): List<ContentHealthCheckItem> {
        val sql = """
            select m.id, m.name, m.workflow_state_id
            from metadata m
            inner join workflow_states ws on m.workflow_state_id = ws.id
            where m.deleted = false
              and m.uploaded is null
              and m.content_length is null
              and m.content_type not like 'bosca/x-%'
              and ws.type not in ('draft', 'pending')
              and not exists (select 1 from documents d where d.metadata_id = m.id)
              and not exists (select 1 from guides g where g.metadata_id = m.id)
              and not exists (select 1 from data d where d.metadata_id = m.id)
              and not exists (select 1 from document_templates dt where dt.metadata_id = m.id)
              and not exists (select 1 from guide_templates gt where gt.metadata_id = m.id)
              and not exists (select 1 from data_templates dat where dat.metadata_id = m.id)
              and not exists (select 1 from collection_templates ct where ct.metadata_id = m.id)
            order by m.modified desc
            limit ? offset ?
        """.trimIndent()
        return executeQuery(sql, limit, offset)
    }

    private suspend fun executeQuery(sql: String, limit: Int, offset: Int): List<ContentHealthCheckItem> {
        val conn = connection()
        return conn.useReadOnlyStatement(sql) { stmt ->
            stmt.setInt(1, limit)
            stmt.setInt(2, offset)
            stmt.executeQuery().use { rs ->
                mapResults(rs)
            }
        }
    }

    private fun mapResults(rs: ResultSet): List<ContentHealthCheckItem> {
        val results = mutableListOf<ContentHealthCheckItem>()
        while (rs.next()) {
            results += ContentHealthCheckItem(
                id = UUID.parse(rs.getString("id")),
                name = rs.getString("name"),
                workflowState = rs.getString("workflow_state_id")
            )
        }
        return results
    }
}
