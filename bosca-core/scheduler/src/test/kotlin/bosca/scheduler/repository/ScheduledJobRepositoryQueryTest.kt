package bosca.scheduler.repository

import bosca.db.annotation.Query
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduledJobRepositoryQueryTest {

    @Test
    fun `due jobs are restricted to scheduler-approved principal states`() {
        val sql = querySql("getDueJobs").normalized()

        assertTrue(sql.contains("principal_state in ('not_required', 'active')"), sql)
        assertTrue(sql.contains("enabled = true"), sql)
    }

    @Test
    fun `principal writes cast through the postgres enum`() {
        assertTrue(querySql("add").contains(":principalState::scheduler.scheduled_job_principal_state"))
        assertTrue(querySql("update").contains(":principalState::scheduler.scheduled_job_principal_state"))
        assertTrue(querySql("assignPrincipal").contains(":state::scheduler.scheduled_job_principal_state"))
        assertTrue(querySql("confirmPrincipal").contains("'active'::scheduler.scheduled_job_principal_state"))
        assertTrue(querySql("clearPrincipal").contains("'needs_principal'::scheduler.scheduled_job_principal_state"))
        assertTrue(querySql("parkNeedsPrincipal").contains("'needs_principal'::scheduler.scheduled_job_principal_state"))
    }

    @Test
    fun `job definition lookup is indexed by job name`() {
        val sql = querySql("getByJobName").normalized()

        assertTrue(sql.contains("where job_name = :jobname"), sql)
        assertTrue(sql.contains("limit :limit offset :offset"), sql)
    }

    private fun querySql(methodName: String): String {
        val method = ScheduledJobRepository::class.java.declaredMethods.firstOrNull { it.name == methodName }
        assertNotNull(method, "method $methodName not found")
        val query = method.getAnnotation(Query::class.java)
        assertNotNull(query, "method $methodName is missing @Query")
        return query.value
    }

    private fun String.normalized(): String = lowercase().replace(Regex("\\s+"), " ").trim()
}
