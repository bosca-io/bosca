package bosca.analytics.experimentation

import bosca.analytics.api.Device
import bosca.analytics.platform.currentDevice
import bosca.core.analytics.InstallationIdProvider
import bosca.core.graphql.AnalyticsDevice
import bosca.core.graphql.EvaluateAllFlags
import bosca.core.graphql.EvaluateFlag
import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.execute

internal class FeatureFlagService(
    private val client: GraphQLClient,
    private val installationIdProvider: InstallationIdProvider,
) {
    suspend fun evaluateAll(): List<FeatureFlag> {
        val installationId = installationIdProvider.getOrCreate()
        return client.execute(
            EvaluateAllFlags,
            EvaluateAllFlags.Variables(
                installationId = installationId,
                device = currentDevice(installationId).toGraphQL(),
            ),
        ).featureFlags.evaluateAll.map { evaluation ->
            FeatureFlag(
                flagKey = evaluation.flagKey,
                value = evaluation.value,
                variationKey = evaluation.variationKey,
                experimentId = evaluation.experimentId?.toString(),
            )
        }
    }

    suspend fun evaluate(flagKey: String): FeatureFlag {
        val installationId = installationIdProvider.getOrCreate()
        val evaluation = client.execute(
            EvaluateFlag,
            EvaluateFlag.Variables(
                flagKey = flagKey,
                installationId = installationId,
                device = currentDevice(installationId).toGraphQL(),
            ),
        ).featureFlags.evaluate
        return FeatureFlag(
            flagKey = evaluation.flagKey,
            value = evaluation.value,
            variationKey = evaluation.variationKey,
            experimentId = evaluation.experimentId?.toString(),
        )
    }
}

private fun Device.toGraphQL(): AnalyticsDevice = AnalyticsDevice(
    installationId = installationId,
    manufacturer = manufacturer,
    model = model,
    platform = platform,
    primaryLocale = primaryLocale,
    systemName = systemName,
    timezone = timezone,
    type = type,
    version = version,
)
