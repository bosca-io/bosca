package bosca.analytics.configuration

import bosca.analytics.events.AnalyticsEventNames
import bosca.analytics.service.AnalyticsScriptBindingService
import bosca.analytics.transform.AnalyticsScriptTransform
import bosca.analytics.transform.ErrorFingerprintTransform
import bosca.analytics.transform.EventPipelineTransform
import bosca.analytics.transform.SessionHeartbeatTransform
import bosca.analytics.livesessions.LiveSessionsService
import bosca.counter.Counter
import bosca.analytics.transform.ScriptTransformPipelineTransform
import bosca.analytics.transform.ScriptTriggerPipelineTransform
import bosca.analytics.transform.geo.CloudflareGeoTransform
import bosca.analytics.transform.geo.GeoPipelineTransform
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.service.SecurityService
import kotlinx.serialization.json.Json

/**
 * Container for the ordered list of pipeline transforms that process analytics events.
 *
 * Transforms are applied sequentially in the order they appear in the list.
 *
 * @param transforms the ordered list of transforms to apply
 */
class EventPipelineTransforms(val transforms: List<EventPipelineTransform>)

/**
 * Configures the analytics event processing pipeline by wiring together
 * geo-enrichment, script transforms, and script trigger (notification) stages.
 *
 * Each script-based stage is instantiated once per event name key, enabling
 * scripts to target specific event types (e.g. `analytics.transform.session`)
 * or the full batch (`analytics.transform.events`).
 */
@Providers
class TransformConfiguration {

    @Provider
    fun cloudflare(): GeoPipelineTransform = CloudflareGeoTransform()

    /**
     * Assembles the full pipeline of event transforms.
     *
     * The pipeline ordering is:
     * 1. Geo-enrichment (Cloudflare headers)
     * 2. Error fingerprinting (assigns stable group identifiers to error events)
     * 3. Direct script bindings — bound scripts run inline over the batch, bypassing pipelines
     * 4. Script transform — triggered pipelines run inline over the whole batch
     * 5. Script trigger — triggered pipelines fire-and-forget over the whole batch
     *
     * Pipelines bind to the batch-level `analytics.transform` / `analytics.notify` events and filter
     * the batch themselves — there is no per-event-type routing. The script/pipeline stages are
     * no-ops wherever their backing services are absent (e.g. the native collector), so this same
     * chain is safe to wire in both the collector and the processor.
     */
    @Provider
    fun transforms(
        geo: GeoPipelineTransform,
        bindingService: ObjectProvider<AnalyticsScriptBindingService>,
        scriptService: ObjectProvider<ScriptService>,
        executionService: ObjectProvider<ScriptExecutionService>,
        securityService: ObjectProvider<SecurityService>,
        json: Json,
        config: EventProcessingConfiguration,
        counter: Counter,
        liveSessions: ObjectProvider<LiveSessionsService>,
    ): EventPipelineTransforms {
        // Ordering: geo enriches lat/lon first; SessionHeartbeatTransform then publishes geo-tagged
        // heartbeats to the live map, counts them into the active-session bucket, and strips them, so no
        // later stage or repository ever sees (or stores) them.
        val transforms = mutableListOf(
            geo,
            SessionHeartbeatTransform(counter, liveSessions),
            ErrorFingerprintTransform(),
        )
        transforms.add(
            AnalyticsScriptTransform(
                bindingService,
                scriptService,
                executionService,
                securityService,
                json,
                config.scriptServiceAccount,
            )
        )
        transforms.add(ScriptTransformPipelineTransform(AnalyticsEventNames.TRANSFORM))
        transforms.add(ScriptTriggerPipelineTransform(AnalyticsEventNames.NOTIFY))
        return EventPipelineTransforms(transforms)
    }
}
