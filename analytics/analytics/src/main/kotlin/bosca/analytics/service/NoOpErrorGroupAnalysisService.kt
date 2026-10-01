package bosca.analytics.service

import bosca.analytics.model.ErrorGroup
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.ProviderName
import bosca.di.annotation.Providers
import org.slf4j.LoggerFactory

/**
 * Fallback [ErrorGroupAnalysisService] used when the AI-backed
 * implementation in `analytics-ai` is not on the classpath. Returns the
 * existing group unchanged and logs a warning. The DI registration is
 * marked low priority so the analytics-ai module's real implementation
 * wins whenever it is present.
 */
class NoOpErrorGroupAnalysisService(
    private val errorGroupService: ErrorGroupService,
) : ErrorGroupAnalysisService {

    override suspend fun analyze(fingerprint: String): ErrorGroup {
        log.warn(
            "AI root-cause analysis was requested for fingerprint={} but no analyzer is configured. " +
                "Add the analytics-ai module to enable this feature.",
            fingerprint,
        )
        return errorGroupService.getByFingerprint(fingerprint)
            ?: error("Error group not found: $fingerprint")
    }

    companion object {
        private val log = LoggerFactory.getLogger(NoOpErrorGroupAnalysisService::class.java)
    }
}

/**
 * DI registration for the analyzer facade. The analytics-ai module can contribute a named
 * implementation; when it is absent, the facade supplies [NoOpErrorGroupAnalysisService].
 */
@Providers
class ErrorGroupAnalysisServiceProvider {

    @Provider
    suspend fun analysisService(
        errorGroupService: ErrorGroupService,
        @ProviderName(AI_PROVIDER)
        aiAnalysisService: ObjectProvider<ErrorGroupAnalysisService>,
    ): ErrorGroupAnalysisService = if (aiAnalysisService.exists) {
        aiAnalysisService.get()
    } else {
        NoOpErrorGroupAnalysisService(errorGroupService)
    }

    companion object {
        const val AI_PROVIDER = "analytics-ai"
    }
}
