package bosca.analytics.transform

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events
import bosca.analytics.service.AnalyticsScriptBindingService
import bosca.di.ObjectProvider
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.slf4j.LoggerFactory

/**
 * Inline transform that runs every enabled [AnalyticsScriptBinding]'s script **directly** —
 * in-process, via [ScriptExecutionService] — bypassing the durable pipeline run machinery and its
 * bookkeeping (runs, dispatch/run jobs, checkpoints, run-log history).
 *
 * The whole event batch is handed to each script as its JSON `input`; the script filters/enriches it
 * however it likes. Whether the script's output is applied is declared on the binding: a
 * [transform][AnalyticsScriptBinding.transform] binding adopts the returned [Events] batch ("pass
 * through" / filter), while a non-transform binding runs the script for its side effects only and
 * ignores the output — it can never mutate the analytics stream. A transform script that returns
 * `null` is a deliberate no-op (the batch is unchanged), but a script or execution failure — including
 * a transform script returning a value that is not an [Events] batch — is **not** swallowed: it
 * propagates so the consumer NAKs the message for redelivery, rather than silently storing the batch
 * un-transformed. This transform therefore must be idempotent (see [EventPipelineTransform]). There is
 * no event-type routing — bindings run on every batch.
 *
 * When there are no enabled bindings the transform returns the batch untouched without resolving any
 * script — the cheap gate that keeps the common (unconfigured) path free of overhead.
 *
 * The scripting/security services are injected as [ObjectProvider]s: where they are absent (e.g. the
 * native collector, which cannot run scripts) the transform is a no-op.
 */
class AnalyticsScriptTransform(
    private val bindingService: ObjectProvider<AnalyticsScriptBindingService>,
    private val scriptService: ObjectProvider<ScriptService>,
    private val executionService: ObjectProvider<ScriptExecutionService>,
    private val securityService: ObjectProvider<SecurityService>,
    private val json: Json,
    private val serviceAccount: String,
) : EventPipelineTransform {

    private val log = LoggerFactory.getLogger(AnalyticsScriptTransform::class.java)

    @Volatile
    private var serviceAccountAuth: AuthenticationContext? = null

    override suspend fun transform(context: EventPipelineContext, events: Events): Events {
        if (!bindingService.exists || !scriptService.exists ||
            !executionService.exists || !securityService.exists
        ) {
            return events
        }

        val bindings = bindingService.get().enabledBindings()
        if (bindings.isEmpty()) return events

        val scripts = scriptService.get()
        val execution = executionService.get()
        val authentication = resolveServiceAccount(securityService.get())

        // Failures propagate: the consumer NAKs the message for redelivery rather than silently
        // storing an un-transformed batch (see EventPipelineTransform's idempotency note).
        var current = events
        for (binding in bindings) {
            current = runBinding(binding, current, scripts, execution, authentication)
        }
        return current
    }

    private suspend fun runBinding(
        binding: AnalyticsScriptBinding,
        events: Events,
        scriptService: ScriptService,
        executionService: ScriptExecutionService,
        authentication: AuthenticationContext,
    ): Events {
        val script = scriptService.get(binding.scriptId)
        if (script == null) {
            log.warn("Analytics script binding {} references missing script {}", binding.id, binding.scriptId)
            return events
        }
        if (!script.enabled) return events

        val input = json.encodeToJsonElement(Events.serializer(), events)
        val scriptContext = DefaultScriptContext(
            authentication,
            CoroutineScope(currentCoroutineContext()),
            input,
            json,
        )
        val result = executionService.executeAsJson(script, scriptContext)
        // A non-transform binding runs the script for its side effects only; its output never
        // mutates the batch (the whole point of declaring transform = false).
        if (!binding.transform) return events
        if (result is JsonNull) return events
        // A transform binding's script must return an Events batch. A malformed / non-Events result
        // is a real failure that must not be swallowed: decoding it throws, the message is NAK'd and
        // redelivered, and the batch is never stored un-transformed (which would silently lose the
        // script's intended transformation).
        return json.decodeFromJsonElement(Events.serializer(), result)
    }

    /**
     * Impersonates the configured service account to run scripts under a non-interactive principal.
     * Memoized: the account is stable for the process lifetime and resolving it hits the database.
     */
    private suspend fun resolveServiceAccount(securityService: SecurityService): AuthenticationContext {
        serviceAccountAuth?.let { return it }
        return securityService.impersonate(serviceAccount).also { serviceAccountAuth = it }
    }
}
