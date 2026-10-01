package bosca.postgres.admin.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.postgres.admin.model.PgActiveQuery
import bosca.postgres.admin.model.PgBlockingChain
import bosca.postgres.admin.model.PgCheckpointStats
import bosca.postgres.admin.model.PgDatabaseStats
import bosca.postgres.admin.model.PgExtension
import bosca.postgres.admin.model.PgIndexStats
import bosca.postgres.admin.model.PgIndexSuggestion
import bosca.postgres.admin.model.PgLockInfo
import bosca.postgres.admin.model.PgLongTransaction
import bosca.postgres.admin.model.PgObjectSize
import bosca.postgres.admin.model.PgReplicationSlot
import bosca.postgres.admin.model.PgReplicationStatus
import bosca.postgres.admin.model.PgSequenceUsage
import bosca.postgres.admin.model.PgSlowQuery
import bosca.postgres.admin.model.PgTableIOStats
import bosca.postgres.admin.model.PgTableStats
import bosca.postgres.admin.model.PgVacuumProgress
import bosca.postgres.admin.model.PgWalStats

/**
 * Provides read-only access to PostgreSQL system catalog views and statistics functions
 * for database health monitoring, performance analysis, and maintenance planning. All
 * queries target system views such as `pg_stat_database`, `pg_stat_activity`,
 * `pg_stat_user_tables`, `pg_locks`, and `pg_settings`. The KSP processor generates
 * the implementation with automatic ResultSet-to-model mapping.
 */
@Repository
interface PostgresAdminRepository {

    // region Database Stats

    @Query(
        """
        SELECT
            d.datname AS database_name,
            s.numbackends,
            s.xact_commit,
            s.xact_rollback,
            s.blks_read,
            s.blks_hit,
            s.tup_returned,
            s.tup_fetched,
            s.tup_inserted,
            s.tup_updated,
            s.tup_deleted,
            s.conflicts,
            s.temp_files,
            s.temp_bytes,
            s.deadlocks,
            CASE WHEN (s.blks_hit + s.blks_read) > 0
                THEN round((s.blks_hit::numeric / (s.blks_hit + s.blks_read)::numeric) * 100, 2)
                ELSE 0
            END AS cache_hit_ratio,
            pg_database_size(d.datname) AS database_size,
            s.stats_reset::text
        FROM pg_stat_database s
        JOIN pg_database d ON d.oid = s.datid
        WHERE d.datname = current_database()
        """
    )
    suspend fun getDatabaseStats(): PgDatabaseStats?

    // endregion

    // region WAL Stats

    @Query(
        """
        SELECT
            wal_records,
            wal_fpi,
            wal_bytes,
            wal_buffers_full,
            wal_write,
            wal_sync,
            wal_write_time,
            wal_sync_time,
            stats_reset::text
        FROM pg_stat_wal
        """
    )
    suspend fun getWalStats(): PgWalStats?

    // endregion

    // region Checkpoint Stats

    @Query(
        """
        SELECT
            checkpoints_timed AS checkpoints_timed_count,
            checkpoints_req AS checkpoints_requested_count,
            buffers_checkpoint,
            buffers_clean,
            maxwritten_clean,
            buffers_backend,
            buffers_backend_fsync,
            buffers_alloc,
            stats_reset::text
        FROM pg_stat_bgwriter
        """
    )
    suspend fun getCheckpointStats(): PgCheckpointStats?

    // endregion

    // region Active Queries

    @Query(
        """
        SELECT
            pid,
            datname AS database_name,
            usename AS user_name,
            application_name,
            client_addr::text,
            state,
            query,
            query_start::text,
            state_change::text,
            xact_start::text,
            EXTRACT(EPOCH FROM (now() - query_start))::float AS query_duration_seconds,
            wait_event_type,
            wait_event,
            backend_start::text,
            backend_type
        FROM pg_stat_activity
        WHERE pid <> pg_backend_pid()
          AND query IS NOT NULL
          AND query <> ''
        ORDER BY query_start ASC NULLS LAST
        """
    )
    suspend fun getActiveQueries(): List<PgActiveQuery>

    @Query(
        """
        SELECT
            pid,
            datname AS database_name,
            usename AS user_name,
            application_name,
            client_addr::text,
            state,
            query,
            query_start::text,
            state_change::text,
            xact_start::text,
            EXTRACT(EPOCH FROM (now() - query_start))::float AS query_duration_seconds,
            wait_event_type,
            wait_event,
            backend_start::text,
            backend_type
        FROM pg_stat_activity
        WHERE pid <> pg_backend_pid()
          AND query IS NOT NULL
          AND query <> ''
          AND EXTRACT(EPOCH FROM (now() - query_start)) >= :minDurationSeconds
        ORDER BY query_start ASC NULLS LAST
        """
    )
    suspend fun getActiveQueriesMinDuration(minDurationSeconds: Float): List<PgActiveQuery>

    // endregion

    // region Slow Queries

    @Query(
        """
        SELECT
            query,
            calls,
            total_exec_time AS total_time_ms,
            mean_exec_time AS mean_time_ms,
            min_exec_time AS min_time_ms,
            max_exec_time AS max_time_ms,
            stddev_exec_time AS stddev_time_ms,
            rows,
            shared_blks_hit,
            shared_blks_read,
            CASE WHEN (shared_blks_hit + shared_blks_read) > 0
                THEN round((shared_blks_hit::numeric / (shared_blks_hit + shared_blks_read)::numeric) * 100, 2)
                ELSE 0
            END AS hit_ratio
        FROM pg_stat_statements
        WHERE query NOT LIKE '%pg_stat_statements%'
        ORDER BY total_exec_time DESC
        LIMIT :limit
        """
    )
    suspend fun getSlowQueriesByTotal(limit: Int): List<PgSlowQuery>

    @Query(
        """
        SELECT
            query,
            calls,
            total_exec_time AS total_time_ms,
            mean_exec_time AS mean_time_ms,
            min_exec_time AS min_time_ms,
            max_exec_time AS max_time_ms,
            stddev_exec_time AS stddev_time_ms,
            rows,
            shared_blks_hit,
            shared_blks_read,
            CASE WHEN (shared_blks_hit + shared_blks_read) > 0
                THEN round((shared_blks_hit::numeric / (shared_blks_hit + shared_blks_read)::numeric) * 100, 2)
                ELSE 0
            END AS hit_ratio
        FROM pg_stat_statements
        WHERE query NOT LIKE '%pg_stat_statements%'
        ORDER BY mean_exec_time DESC
        LIMIT :limit
        """
    )
    suspend fun getSlowQueriesByMean(limit: Int): List<PgSlowQuery>

    // endregion

    // region Table Stats

    @Query(
        """
        SELECT
            s.schemaname AS schema_name,
            s.relname AS table_name,
            COALESCE(s.seq_scan, 0) AS seq_scan,
            COALESCE(s.seq_tup_read, 0) AS seq_tup_read,
            COALESCE(s.idx_scan, 0) AS idx_scan,
            COALESCE(s.idx_tup_fetch, 0) AS idx_tup_fetch,
            COALESCE(s.n_tup_ins, 0) AS n_tup_ins,
            COALESCE(s.n_tup_upd, 0) AS n_tup_upd,
            COALESCE(s.n_tup_del, 0) AS n_tup_del,
            COALESCE(s.n_tup_hot_upd, 0) AS n_tup_hot_upd,
            COALESCE(s.n_live_tup, 0) AS n_live_tup,
            COALESCE(s.n_dead_tup, 0) AS n_dead_tup,
            s.last_vacuum::text,
            s.last_autovacuum::text,
            s.last_analyze::text,
            s.last_autoanalyze::text,
            COALESCE(s.vacuum_count, 0) AS vacuum_count,
            COALESCE(s.autovacuum_count, 0) AS autovacuum_count,
            COALESCE(s.analyze_count, 0) AS analyze_count,
            COALESCE(s.autoanalyze_count, 0) AS autoanalyze_count,
            pg_total_relation_size(c.oid) AS total_size,
            pg_table_size(c.oid) AS table_size,
            pg_indexes_size(c.oid) AS index_size,
            CASE WHEN s.n_live_tup > 0
                THEN round((s.n_dead_tup::numeric / s.n_live_tup::numeric) * 100, 2)
                ELSE 0
            END AS bloat_ratio
        FROM pg_stat_user_tables s
        JOIN pg_class c ON c.relname = s.relname
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = s.schemaname
        WHERE s.schemaname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
        ORDER BY pg_total_relation_size(c.oid) DESC
        """
    )
    suspend fun getTableStats(): List<PgTableStats>

    @Query(
        """
        SELECT
            s.schemaname AS schema_name,
            s.relname AS table_name,
            COALESCE(s.seq_scan, 0) AS seq_scan,
            COALESCE(s.seq_tup_read, 0) AS seq_tup_read,
            COALESCE(s.idx_scan, 0) AS idx_scan,
            COALESCE(s.idx_tup_fetch, 0) AS idx_tup_fetch,
            COALESCE(s.n_tup_ins, 0) AS n_tup_ins,
            COALESCE(s.n_tup_upd, 0) AS n_tup_upd,
            COALESCE(s.n_tup_del, 0) AS n_tup_del,
            COALESCE(s.n_tup_hot_upd, 0) AS n_tup_hot_upd,
            COALESCE(s.n_live_tup, 0) AS n_live_tup,
            COALESCE(s.n_dead_tup, 0) AS n_dead_tup,
            s.last_vacuum::text,
            s.last_autovacuum::text,
            s.last_analyze::text,
            s.last_autoanalyze::text,
            COALESCE(s.vacuum_count, 0) AS vacuum_count,
            COALESCE(s.autovacuum_count, 0) AS autovacuum_count,
            COALESCE(s.analyze_count, 0) AS analyze_count,
            COALESCE(s.autoanalyze_count, 0) AS autoanalyze_count,
            pg_total_relation_size(c.oid) AS total_size,
            pg_table_size(c.oid) AS table_size,
            pg_indexes_size(c.oid) AS index_size,
            CASE WHEN s.n_live_tup > 0
                THEN round((s.n_dead_tup::numeric / s.n_live_tup::numeric) * 100, 2)
                ELSE 0
            END AS bloat_ratio
        FROM pg_stat_user_tables s
        JOIN pg_class c ON c.relname = s.relname
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = s.schemaname
        WHERE s.schemaname = :schemaName
        ORDER BY pg_total_relation_size(c.oid) DESC
        """
    )
    suspend fun getTableStatsBySchema(schemaName: String): List<PgTableStats>

    // endregion

    // region Index Stats

    @Query(
        """
        SELECT
            s.schemaname AS schema_name,
            s.relname AS table_name,
            s.indexrelname AS index_name,
            COALESCE(s.idx_scan, 0) AS idx_scan,
            COALESCE(s.idx_tup_read, 0) AS idx_tup_read,
            COALESCE(s.idx_tup_fetch, 0) AS idx_tup_fetch,
            pg_relation_size(i.indexrelid) AS index_size,
            pg_get_indexdef(i.indexrelid) AS index_def
        FROM pg_stat_user_indexes s
        JOIN pg_index i ON i.indexrelid = s.indexrelid
        WHERE s.schemaname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
        ORDER BY s.idx_scan DESC
        """
    )
    suspend fun getIndexStats(): List<PgIndexStats>

    @Query(
        """
        SELECT
            s.schemaname AS schema_name,
            s.relname AS table_name,
            s.indexrelname AS index_name,
            COALESCE(s.idx_scan, 0) AS idx_scan,
            COALESCE(s.idx_tup_read, 0) AS idx_tup_read,
            COALESCE(s.idx_tup_fetch, 0) AS idx_tup_fetch,
            pg_relation_size(i.indexrelid) AS index_size,
            pg_get_indexdef(i.indexrelid) AS index_def
        FROM pg_stat_user_indexes s
        JOIN pg_index i ON i.indexrelid = s.indexrelid
        WHERE s.schemaname = :schemaName
        ORDER BY s.idx_scan DESC
        """
    )
    suspend fun getIndexStatsBySchema(schemaName: String): List<PgIndexStats>

    // endregion

    // region Unused Indexes

    @Query(
        """
        SELECT
            s.schemaname AS schema_name,
            s.relname AS table_name,
            s.indexrelname AS index_name,
            COALESCE(s.idx_scan, 0) AS idx_scan,
            COALESCE(s.idx_tup_read, 0) AS idx_tup_read,
            COALESCE(s.idx_tup_fetch, 0) AS idx_tup_fetch,
            pg_relation_size(i.indexrelid) AS index_size,
            pg_get_indexdef(i.indexrelid) AS index_def
        FROM pg_stat_user_indexes s
        JOIN pg_index i ON i.indexrelid = s.indexrelid
        JOIN pg_class c ON c.oid = s.relid
        WHERE s.idx_scan = 0
          AND NOT i.indisunique
          AND NOT i.indisprimary
          AND s.schemaname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
          AND pg_table_size(c.oid) >= :minTableSize
        ORDER BY pg_relation_size(i.indexrelid) DESC
        """
    )
    suspend fun getUnusedIndexes(minTableSize: Long): List<PgIndexStats>

    // endregion

    // region Index Suggestions

    @Query(
        """
        SELECT
            s.schemaname AS schema_name,
            s.relname AS table_name,
            COALESCE(s.seq_scan, 0) AS seq_scan,
            COALESCE(s.seq_tup_read, 0) AS seq_tup_read,
            COALESCE(s.idx_scan, 0) AS idx_scan,
            pg_total_relation_size(c.oid) AS table_size,
            CASE
                WHEN COALESCE(s.idx_scan, 0) = 0 THEN 'No index scans detected; ' || COALESCE(s.seq_scan, 0) || ' sequential scans reading ' || COALESCE(s.seq_tup_read, 0) || ' rows on a ' || pg_size_pretty(pg_total_relation_size(c.oid)) || ' table'
                WHEN COALESCE(s.seq_scan, 0) > COALESCE(s.idx_scan, 0) * 100 THEN 'Sequential scans (' || COALESCE(s.seq_scan, 0) || ') vastly outnumber index scans (' || COALESCE(s.idx_scan, 0) || ') on a ' || pg_size_pretty(pg_total_relation_size(c.oid)) || ' table'
                ELSE 'High sequential scan count (' || COALESCE(s.seq_scan, 0) || ') vs index scans (' || COALESCE(s.idx_scan, 0) || ') with ' || COALESCE(s.seq_tup_read, 0) || ' rows read sequentially'
            END AS reason
        FROM pg_stat_user_tables s
        JOIN pg_class c ON c.relname = s.relname
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = s.schemaname
        WHERE s.seq_scan >= :minSeqScans
          AND s.schemaname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
          AND (s.idx_scan = 0 OR s.seq_scan > s.idx_scan * 10)
        ORDER BY s.seq_scan DESC
        """
    )
    suspend fun getIndexSuggestions(minSeqScans: Long): List<PgIndexSuggestion>

    // endregion

    // region Locks

    @Query(
        """
        SELECT
            l.pid,
            l.locktype AS lock_type,
            d.datname AS database_name,
            COALESCE(c.relname, l.locktype || ':' || COALESCE(l.objid::text, l.transactionid::text, '')) AS relation_name,
            l.mode,
            l.granted,
            a.query,
            a.state,
            EXTRACT(EPOCH FROM (now() - a.query_start))::float AS duration_seconds
        FROM pg_locks l
        LEFT JOIN pg_stat_activity a ON a.pid = l.pid
        LEFT JOIN pg_class c ON c.oid = l.relation
        LEFT JOIN pg_database d ON d.oid = l.database
        WHERE l.pid <> pg_backend_pid()
        ORDER BY l.granted ASC, duration_seconds DESC NULLS LAST
        """
    )
    suspend fun getLocks(): List<PgLockInfo>

    // endregion

    // region Replication Slots

    @Query(
        """
        SELECT
            slot_name,
            slot_type,
            active,
            database AS database_name,
            confirmed_flush_lsn::text,
            CASE WHEN pg_is_in_recovery() THEN NULL
                 ELSE pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint
            END AS retained_wal_bytes
        FROM pg_replication_slots
        ORDER BY slot_name
        """
    )
    suspend fun getReplicationSlots(): List<PgReplicationSlot>

    // endregion

    // region Vacuum Progress

    @Query(
        """
        SELECT
            p.pid,
            d.datname AS database_name,
            n.nspname AS schema_name,
            c.relname AS table_name,
            p.phase,
            p.heap_blks_total,
            p.heap_blks_scanned,
            p.heap_blks_vacuumed,
            p.num_dead_item_ids AS num_dead_tuples
        FROM pg_stat_progress_vacuum p
        LEFT JOIN pg_database d ON d.oid = p.datid
        LEFT JOIN pg_class c ON c.oid = p.relid
        LEFT JOIN pg_namespace n ON n.oid = c.relnamespace
        ORDER BY p.pid
        """
    )
    suspend fun getVacuumProgress(): List<PgVacuumProgress>

    // endregion

    // region Table IO Stats

    @Query(
        """
        SELECT
            schemaname AS schema_name,
            relname AS table_name,
            COALESCE(heap_blks_read, 0) AS heap_blks_read,
            COALESCE(heap_blks_hit, 0) AS heap_blks_hit,
            COALESCE(idx_blks_read, 0) AS idx_blks_read,
            COALESCE(idx_blks_hit, 0) AS idx_blks_hit,
            COALESCE(toast_blks_read, 0) AS toast_blks_read,
            COALESCE(toast_blks_hit, 0) AS toast_blks_hit,
            CASE WHEN (COALESCE(heap_blks_hit, 0) + COALESCE(heap_blks_read, 0)) > 0
                THEN round((COALESCE(heap_blks_hit, 0)::numeric / (COALESCE(heap_blks_hit, 0) + COALESCE(heap_blks_read, 0))::numeric) * 100, 2)
                ELSE 0
            END AS cache_hit_ratio
        FROM pg_statio_user_tables
        WHERE schemaname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
        ORDER BY (COALESCE(heap_blks_read, 0) + COALESCE(idx_blks_read, 0)) DESC
        """
    )
    suspend fun getTableIOStats(): List<PgTableIOStats>

    @Query(
        """
        SELECT
            schemaname AS schema_name,
            relname AS table_name,
            COALESCE(heap_blks_read, 0) AS heap_blks_read,
            COALESCE(heap_blks_hit, 0) AS heap_blks_hit,
            COALESCE(idx_blks_read, 0) AS idx_blks_read,
            COALESCE(idx_blks_hit, 0) AS idx_blks_hit,
            COALESCE(toast_blks_read, 0) AS toast_blks_read,
            COALESCE(toast_blks_hit, 0) AS toast_blks_hit,
            CASE WHEN (COALESCE(heap_blks_hit, 0) + COALESCE(heap_blks_read, 0)) > 0
                THEN round((COALESCE(heap_blks_hit, 0)::numeric / (COALESCE(heap_blks_hit, 0) + COALESCE(heap_blks_read, 0))::numeric) * 100, 2)
                ELSE 0
            END AS cache_hit_ratio
        FROM pg_statio_user_tables
        WHERE schemaname = :schemaName
        ORDER BY (COALESCE(heap_blks_read, 0) + COALESCE(idx_blks_read, 0)) DESC
        """
    )
    suspend fun getTableIOStatsBySchema(schemaName: String): List<PgTableIOStats>

    // endregion

    // region Bloated Tables

    @Query(
        """
        SELECT
            s.schemaname AS schema_name,
            s.relname AS table_name,
            COALESCE(s.seq_scan, 0) AS seq_scan,
            COALESCE(s.seq_tup_read, 0) AS seq_tup_read,
            COALESCE(s.idx_scan, 0) AS idx_scan,
            COALESCE(s.idx_tup_fetch, 0) AS idx_tup_fetch,
            COALESCE(s.n_tup_ins, 0) AS n_tup_ins,
            COALESCE(s.n_tup_upd, 0) AS n_tup_upd,
            COALESCE(s.n_tup_del, 0) AS n_tup_del,
            COALESCE(s.n_tup_hot_upd, 0) AS n_tup_hot_upd,
            COALESCE(s.n_live_tup, 0) AS n_live_tup,
            COALESCE(s.n_dead_tup, 0) AS n_dead_tup,
            s.last_vacuum::text,
            s.last_autovacuum::text,
            s.last_analyze::text,
            s.last_autoanalyze::text,
            COALESCE(s.vacuum_count, 0) AS vacuum_count,
            COALESCE(s.autovacuum_count, 0) AS autovacuum_count,
            COALESCE(s.analyze_count, 0) AS analyze_count,
            COALESCE(s.autoanalyze_count, 0) AS autoanalyze_count,
            pg_total_relation_size(c.oid) AS total_size,
            pg_table_size(c.oid) AS table_size,
            pg_indexes_size(c.oid) AS index_size,
            CASE WHEN s.n_live_tup > 0
                THEN round((s.n_dead_tup::numeric / s.n_live_tup::numeric) * 100, 2)
                ELSE 0
            END AS bloat_ratio
        FROM pg_stat_user_tables s
        JOIN pg_class c ON c.relname = s.relname
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = s.schemaname
        WHERE s.schemaname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
          AND s.n_live_tup > 0
          AND (s.n_dead_tup::numeric / s.n_live_tup::numeric) * 100 >= :minBloatPercent
        ORDER BY (s.n_dead_tup::numeric / s.n_live_tup::numeric) DESC
        """
    )
    suspend fun getBloatedTables(minBloatPercent: Double): List<PgTableStats>

    // endregion

    // region Blocking Chains

    @Query(
        """
        SELECT
            blocked.pid AS blocked_pid,
            blocked.usename AS blocked_user,
            blocked.query AS blocked_query,
            blocked.state AS blocked_state,
            EXTRACT(EPOCH FROM (now() - blocked.query_start))::float AS blocked_duration_seconds,
            blocking.pid AS blocking_pid,
            blocking.usename AS blocking_user,
            blocking.query AS blocking_query,
            blocking.state AS blocking_state
        FROM pg_stat_activity blocked
        JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS blocking_pid ON true
        JOIN pg_stat_activity blocking ON blocking.pid = blocking_pid
        WHERE blocked.pid <> pg_backend_pid()
          AND cardinality(pg_blocking_pids(blocked.pid)) > 0
        ORDER BY blocked_duration_seconds DESC NULLS LAST
        """
    )
    suspend fun getBlockingChains(): List<PgBlockingChain>

    // endregion

    // region Long Running Transactions

    @Query(
        """
        SELECT
            pid,
            datname AS database_name,
            usename AS user_name,
            application_name,
            state,
            xact_start::text AS xact_start_time,
            EXTRACT(EPOCH FROM (now() - xact_start))::float AS xact_duration_seconds,
            query AS last_query,
            wait_event_type,
            wait_event
        FROM pg_stat_activity
        WHERE xact_start IS NOT NULL
          AND pid <> pg_backend_pid()
          AND EXTRACT(EPOCH FROM (now() - xact_start)) >= :minDurationSeconds
        ORDER BY xact_start ASC
        """
    )
    suspend fun getLongRunningTransactions(minDurationSeconds: Float): List<PgLongTransaction>

    // endregion

    // region Sequence Usage

    @Query(
        """
        SELECT
            n.nspname AS schema_name,
            c.relname AS sequence_name,
            s.data_type,
            COALESCE(s.last_value, s.start_value) AS current_value,
            s.maximum_value AS max_value,
            CASE WHEN s.maximum_value > 0
                THEN round((COALESCE(s.last_value, s.start_value)::numeric / s.maximum_value::numeric) * 100, 4)
                ELSE 0
            END AS percent_used
        FROM information_schema.sequences s
        JOIN pg_class c ON c.relname = s.sequence_name
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = s.sequence_schema
        WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
          AND CASE WHEN s.maximum_value > 0
                THEN (COALESCE(s.last_value, s.start_value)::numeric / s.maximum_value::numeric) * 100
                ELSE 0
              END >= :minPercentUsed
        ORDER BY percent_used DESC
        """
    )
    suspend fun getSequenceUsage(minPercentUsed: Float): List<PgSequenceUsage>

    // endregion

    // region Database Size Breakdown

    @Query(
        """
        SELECT
            n.nspname AS schema_name,
            c.relname AS object_name,
            CASE c.relkind
                WHEN 'r' THEN 'table'
                WHEN 'i' THEN 'index'
                WHEN 'm' THEN 'materialized view'
                WHEN 't' THEN 'toast table'
                ELSE c.relkind::text
            END AS object_type,
            pg_total_relation_size(c.oid) AS total_size,
            CASE WHEN c.relkind = 'r' THEN pg_table_size(c.oid) ELSE NULL END AS table_size,
            CASE WHEN c.relkind = 'r' THEN pg_indexes_size(c.oid) ELSE NULL END AS index_size
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
          AND c.relkind IN ('r', 'i', 'm')
        ORDER BY pg_total_relation_size(c.oid) DESC
        LIMIT :limit
        """
    )
    suspend fun getDatabaseSizeBreakdown(limit: Int): List<PgObjectSize>

    // endregion

    // region Installed Extensions

    @Query(
        """
        SELECT
            e.extname AS name,
            e.extversion AS installed_version,
            a.default_version,
            a.comment AS description
        FROM pg_extension e
        LEFT JOIN pg_available_extensions a ON a.name = e.extname
        ORDER BY e.extname
        """
    )
    suspend fun getInstalledExtensions(): List<PgExtension>

    // endregion

    // region Scalar Queries

    @Query("SELECT version() AS result")
    suspend fun getServerVersion(): String?

    @Query("SELECT (now() - pg_postmaster_start_time())::text AS result")
    suspend fun getUptime(): String?

    @Query("SELECT pg_cancel_backend(:pid) AS result")
    suspend fun cancelQuery(pid: Int): Boolean?

    @Query("SELECT pg_terminate_backend(:pid) AS result")
    suspend fun terminateBackend(pid: Int): Boolean?

    @Query("SELECT 1 AS result FROM pg_extension WHERE extname = :extensionName")
    suspend fun checkExtensionInstalled(extensionName: String): Int?

    @Query("SELECT pg_stat_statements_reset()::text AS result")
    suspend fun resetStatStatements(): String?

    // endregion

    // region Replication Status

    @Query(
        """
        SELECT
            pid,
            usename AS user_name,
            application_name,
            client_addr::text,
            state,
            sent_lsn::text,
            write_lsn::text,
            flush_lsn::text,
            replay_lsn::text,
            EXTRACT(EPOCH FROM replay_lag)::float AS replay_lag_seconds,
            EXTRACT(EPOCH FROM write_lag)::float AS write_lag_seconds,
            EXTRACT(EPOCH FROM flush_lag)::float AS flush_lag_seconds,
            sync_state,
            sync_priority
        FROM pg_stat_replication
        ORDER BY application_name
        """
    )
    suspend fun getReplicationStatus(): List<PgReplicationStatus>

    @Query("SELECT pg_is_in_recovery() AS result")
    suspend fun isInRecovery(): Boolean?

    // endregion
}
