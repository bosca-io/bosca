package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SystemBibleModel(
    val admin: Boolean = false,
    var providers: List<String> = emptyList(),
    var configurations: List<SystemBibleConfigurationsData.Content.BibleProviders.Configurations> = emptyList(),
    var configurationId: String = "",
    var sources: List<SystemBibleSourcesData.Content.BibleProviders.Sources> = emptyList(),
    var catalog: List<SystemBibleCatalogData.Content.BibleProviders.Catalog> = emptyList(),
    var kind: String = "TEXT", var search: String = "", var offset: Int = 0, var catalogLoaded: Boolean = false,
    var mode: String = "", var dialogRequest: Long = 0,
    var draft: SystemBibleConfigurationDraft = SystemBibleConfigurationDraft(),
    var registration: SystemBibleCatalogData.Content.BibleProviders.Catalog? = null,
    var variant: String = "", var allowedUses: List<BibleContentUse> = emptyList(), var targetKey: String = "",
    var targets: List<SystemBibleAudioTargetsData.Content.BibleProviders.AudioTargets> = emptyList(),
    var targetsLoaded: Boolean = false,
    var removingSource: SystemBibleSourcesData.Content.BibleProviders.Sources? = null,
    var removingSources: List<SystemBibleSourcesData.Content.BibleProviders.Sources> = emptyList(),
    var purgeSources: Boolean = false,
    val feedback: SystemFeedback = SystemFeedback(),
    val catalogFeedback: SystemFeedback = SystemFeedback(),
    val targetsFeedback: SystemFeedback = SystemFeedback(),
) {
    val selected get() = configurations.firstOrNull { it.id == configurationId }
    val audioRegistration get() = registration?.kind == BibleSourceKind.AUDIO
    val useOptions get() = if (audioRegistration) listOf(BibleContentUse.PLAYBACK) else BibleContentUse.entries.filter { it != BibleContentUse.PLAYBACK }
    val needsCatalog get() = admin && selected?.enabled == true && !catalogLoaded
    val needsTargets get() = mode == "register" && audioRegistration && !targetsLoaded

    suspend fun reload(ctx: RenderContext) {
        if (!admin) return
        val result = ctx.gql.execute(SystemBibleConfigurations, Unit).content.bibleProviders
        providers = result.providers; configurations = result.configurations
        if (configurations.none { it.id == configurationId }) configurationId = configurations.firstOrNull()?.id.orEmpty()
        reloadSources(ctx)
    }

    private suspend fun reloadSources(ctx: RenderContext) {
        sources = if (configurationId.isBlank()) emptyList() else ctx.gql.execute(SystemBibleSources, SystemBibleSources.Variables(configurationId)).content.bibleProviders.sources
    }

    suspend fun selectConfiguration(id: String, ctx: RenderContext) = feedback.perform {
        require(admin) { "Administrator access is required." }
        if (id.isBlank() && configurations.isEmpty()) return@perform
        require(configurations.any { it.id == id }) { "Select a saved configuration." }
        configurationId = id; resetCatalog(); cancel(); sources = emptyList(); reloadSources(ctx)
    }

    private fun resetCatalog() {
        catalog = emptyList(); offset = 0; catalogLoaded = false
        catalogFeedback.failed = false; catalogFeedback.message = ""
    }

    fun cancel() {
        feedback.failed = false; feedback.message = ""
        mode = ""; draft = SystemBibleConfigurationDraft(); registration = null
        targets = emptyList(); targetsLoaded = false; variant = ""; targetKey = ""; allowedUses = emptyList()
        removingSource = null; removingSources = emptyList(); purgeSources = false
    }

    private fun open(action: String) {
        cancel(); mode = action; dialogRequest++
        feedback.failed = false; feedback.message = ""
        targetsFeedback.failed = false; targetsFeedback.message = ""
    }

    fun editConfiguration(create: Boolean) {
        open("configuration")
        val config = if (create) null else selected
        draft = SystemBibleConfigurationDraft(config?.id.orEmpty(), config?.name.orEmpty(), config?.provider ?: providers.firstOrNull().orEmpty(), config?.enabled ?: true, hasApiKey = config?.hasApiKey ?: false)
    }

    suspend fun saveConfiguration(name: String, provider: String, enabled: Boolean, apiKey: String, ctx: RenderContext) = feedback.perform {
        draft = draft.copy(name = name, provider = provider, enabled = enabled, apiKey = apiKey); mode = "configuration"
        require(admin) { "Administrator access is required." }
        require(name.isNotBlank()) { "Enter a configuration name." }
        require(provider in providers) { "Select a provider." }
        require(apiKey.isNotBlank() || draft.hasApiKey) { "Enter the provider API key." }
        val saved = ctx.gql.execute(SystemSaveBibleConfiguration, SystemSaveBibleConfiguration.Variables(BibleProviderConfigurationInput(draft.id.takeIf(String::isNotBlank), name.trim(), provider, enabled, apiKey.trim().takeIf(String::isNotEmpty)))).content.bibleProviders.saveConfiguration
        configurationId = saved.id; cancel(); resetCatalog(); reload(ctx)
        feedback.message = "Bible provider configuration saved."
    }

    suspend fun testConnection(ctx: RenderContext) = feedback.perform {
        require(admin) { "Administrator access is required." }
        check(ctx.gql.execute(SystemTestBibleConfiguration, SystemTestBibleConfiguration.Variables(configurationId)).content.bibleProviders.test) { "Bible provider connection failed." }
        feedback.message = "Bible provider connection succeeded."
    }

    suspend fun loadCatalog(nextKind: String, query: String, nextOffset: Int, ctx: RenderContext) = catalogFeedback.perform {
        require(admin) { "Administrator access is required." }
        kind = nextKind; search = query; offset = nextOffset.coerceAtLeast(0); catalog = emptyList(); catalogLoaded = true
        if (selected?.enabled != true) return@perform
        catalog = ctx.gql.execute(SystemBibleCatalog, SystemBibleCatalog.Variables(configurationId, BibleSourceKind.valueOf(kind), search.trim().takeIf(String::isNotEmpty), offset, 20)).content.bibleProviders.catalog
    }

    fun prepareRegistration(id: String) {
        require(selected?.enabled == true) { "Enable this configuration first." }
        val entry = catalog.single { it.id == id }
        open("register"); registration = entry
    }

    suspend fun loadTargets(ctx: RenderContext) = targetsFeedback.perform {
        require(admin) { "Administrator access is required." }
        targetsLoaded = true; targets = emptyList()
        val entry = requireNotNull(registration) { "Select a recording." }
        require(entry.kind == BibleSourceKind.AUDIO) { "Select an audio recording." }
        targets = ctx.gql.execute(SystemBibleAudioTargets, SystemBibleAudioTargets.Variables(configurationId, entry.id)).content.bibleProviders.audioTargets
    }

    suspend fun register(nextVariant: String, uses: String, key: String, ctx: RenderContext) = feedback.perform {
        variant = nextVariant; targetKey = key
        allowedUses = Json.decodeFromString(ListSerializer(BibleContentUse.serializer()), uses.ifBlank { "[]" })
        require(admin) { "Administrator access is required." }
        require(selected?.enabled == true) { "Enable this configuration first." }
        val entry = requireNotNull(registration) { "Select a catalog entry." }
        require(allowedUses.all { it in useOptions }) { "Select uses appropriate for this source." }
        if (entry.kind == BibleSourceKind.TEXT) {
            require(variant.isNotBlank()) { "Enter a Bible variant." }
            ctx.gql.execute(SystemAddProviderBible, SystemAddProviderBible.Variables(AddProviderBibleInput(configurationId, entry.id, variant.trim(), allowedUses)))
        } else {
            val target = targets.singleOrNull { targetKey(it) == key } ?: error("Select a compatible Bible variant.")
            ctx.gql.execute(SystemAddProviderAudio, SystemAddProviderAudio.Variables(target.metadataId, target.version, target.variant, AddProviderAudioInput(configurationId, entry.id, allowedUses)))
        }
        cancel(); reloadSources(ctx); feedback.message = "Bible source registered."
    }

    suspend fun refreshSource(id: String, ctx: RenderContext) = feedback.perform {
        require(admin && selected?.enabled == true) { "Enable this configuration first." }
        check(ctx.gql.execute(SystemRefreshBibleSource, SystemRefreshBibleSource.Variables(id)).content.bibleProviders.refresh) { "Bible source was not refreshed." }
        reloadSources(ctx); feedback.message = "Bible source refreshed."
    }

    fun prepareRemoveSource(id: String) {
        val source = sources.single { it.id == id }
        open("source"); removingSource = source
    }

    suspend fun removeSource(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(admin && confirmed) { "Confirm source removal." }
        val source = requireNotNull(removingSource) { "Select a source." }
        check(ctx.gql.execute(SystemRemoveBibleSource, SystemRemoveBibleSource.Variables(source.id, source.version)).content.bibleProviders.removeSource) { "Bible source was not removed." }
        cancel(); reloadSources(ctx); feedback.message = "Bible source removed."
    }

    suspend fun prepareRemoveConfiguration(ctx: RenderContext) = feedback.perform {
        require(admin) { "Administrator access is required." }
        reloadSources(ctx); open("removeConfiguration"); removingSources = sources.toList()
    }

    suspend fun removeConfiguration(confirmed: Boolean, purge: Boolean, ctx: RenderContext) = feedback.perform {
        purgeSources = purge
        require(admin && confirmed) { "Confirm configuration removal." }
        require(removingSources.isEmpty() || purge) { "Confirm removal of all listed sources." }
        check(ctx.gql.execute(SystemRemoveBibleConfiguration, SystemRemoveBibleConfiguration.Variables(configurationId, purge && removingSources.isNotEmpty())).content.bibleProviders.removeConfiguration) { "Bible provider configuration was not removed." }
        cancel(); resetCatalog(); reload(ctx); feedback.message = "Bible provider configuration removed."
    }

    fun targetKey(target: SystemBibleAudioTargetsData.Content.BibleProviders.AudioTargets) = "${target.metadataId}:${target.version}:${target.variant}"
    fun providerLabel(provider: String) = if (provider == "API_BIBLE") "API.Bible" else provider
    fun useLabel(use: BibleContentUse) = if (use == BibleContentUse.AI_CONTEXT) "AI context" else use.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

suspend fun systemBible(): SystemBibleModel {
    val ctx = currentRenderContext()
    return SystemBibleModel(admin = systemAdministrator(ctx)).also { model -> model.feedback.perform { model.reload(ctx) } }
}
