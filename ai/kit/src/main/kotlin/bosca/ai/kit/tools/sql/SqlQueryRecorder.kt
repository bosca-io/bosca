package bosca.ai.kit.tools.sql

import bosca.ai.kit.tools.analytics.InvestigationRecorder

/**
 * Coroutine context element that carries every SQL statement a run **actually executed**.
 *
 * An action that must source its answer (show the user the query behind the numbers) installs a
 * recorder around its sub-agent run, and [ExecuteQueryTool] records each statement that executed
 * successfully. The recorded SQL is ground truth — captured on the execution path itself — unlike
 * the model's structured answer, which merely *claims* what it ran and can paraphrase. Same
 * ambient-element pattern as [bosca.ai.kit.tools.KitToolContext].
 *
 * The whole execution history is kept (not just the last statement) because the tool loop is free
 * to run further statements *after* the one that sourced the answer — a sanity `COUNT`, a schema
 * probe — and the last-executed statement is then not the one behind the numbers. Callers resolve
 * the answering statement with [findExecuted] and use [lastQuery] only as a fallback.
 */
@Deprecated("Use InvestigationRecorder; retained for source compatibility")
class SqlQueryRecorder : InvestigationRecorder() {
    /** Record a successful ad-hoc query using the legacy API. */
    fun record(sql: String) {
        record(
            kind = bosca.ai.chat.model.AnalyticsInvestigationKind.QUERY,
            tool = "execute_query",
            sql = sql,
            resultSummary = "query executed",
        )
    }
}
