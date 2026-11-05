package bosca.ai.chat.graphql

import bosca.ai.kit.harness.KitHarnessSettings
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class KitHarnessSettingsController : GraphQLController<KitHarnessSettings> {
    @Field fun policyVersion(settings: KitHarnessSettings) = settings.policyVersion
    @Field fun effectiveProfile(settings: KitHarnessSettings) = settings.effectiveProfile
    @Field fun source(settings: KitHarnessSettings) = settings.source
    @Field fun canOverride(settings: KitHarnessSettings) = settings.canOverride
    @Field fun choices(settings: KitHarnessSettings) = settings.choices
    @Field fun requestedProfile(settings: KitHarnessSettings) = settings.requestedProfile
    @Field fun notice(settings: KitHarnessSettings) = settings.notice
    @Field fun mcpOptions(settings: KitHarnessSettings) = settings.mcpOptions
    @Field fun mcpServers(settings: KitHarnessSettings) = settings.mcpServers
    @Field fun requestedMcpServers(settings: KitHarnessSettings) = settings.requestedMcpServers
    @Field fun mcpNotices(settings: KitHarnessSettings) = settings.mcpNotices
    @Field fun capabilityVersion(settings: KitHarnessSettings) = settings.capabilityVersion
    @Field fun model(settings: KitHarnessSettings) = settings.model
    @Field fun reasoning(settings: KitHarnessSettings) = settings.reasoning
    @Field fun modelSource(settings: KitHarnessSettings) = settings.modelSource
    @Field fun reasoningSource(settings: KitHarnessSettings) = settings.reasoningSource
    @Field fun requestedModel(settings: KitHarnessSettings) = settings.requestedModel
    @Field fun requestedReasoning(settings: KitHarnessSettings) = settings.requestedReasoning
    @Field fun models(settings: KitHarnessSettings) = settings.models
    @Field fun reasoningOptions(settings: KitHarnessSettings) = settings.reasoningOptions
}
