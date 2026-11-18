package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
class SystemUtilitiesModel(val admin: Boolean = false, val feedback: SystemFeedback = SystemFeedback()) {
    suspend fun execute(action: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(admin) { "Administrator access required." }
        val result = when (action) {
            "clear-cache" -> ctx.gql.execute(SystemClearCache, Unit).clearCache
            "clear-cdn-cache" -> ctx.gql.execute(SystemClearCdnCache, Unit).clearCdnCache
            "expire-all-jobs" -> {
                require(confirmed) { "Confirm job expiration." }
                ctx.gql.execute(SystemExpireAllJobs, Unit).expireAllJobs
            }
            "clear-job-locks" -> {
                require(confirmed) { "Confirm releasing job locks." }
                ctx.gql.execute(SystemClearJobLocks, Unit).clearJobLocks
            }
            else -> error("Unknown utility action.")
        }
        check(result) { "The operation did not complete." }
        feedback.message = when (action) {
            "clear-cache" -> "Server caches cleared."
            "clear-cdn-cache" -> "CDN cache cleared."
            "expire-all-jobs" -> "Jobs expired."
            else -> "Job locks released."
        }
    }
}

suspend fun systemUtilities(): SystemUtilitiesModel = SystemUtilitiesModel(systemAdministrator(currentRenderContext()))
