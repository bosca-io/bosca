package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.ViewTab
import bosca.system.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
class NatsConsoleModel(
    val admin: Boolean,
    var tab: String = "overview",
    var overview: SystemGetNatsAdminOverviewData.NatsAdmin? = null,
    var streamFilter: String = "", var bucketFilter: String = "", var connectionFilter: String = "",
    var detail: String = "", var streamName: String = "", var bucketName: String = "",
    var streamRequest: Long = 0, var bucketRequest: Long = 0, var refreshes: Long = 0,
    var consumers: List<SystemGetJetStreamConsumersData.NatsAdmin.JetStreamConsumers> = emptyList(),
    var messages: List<SystemGetStreamMessagesData.NatsAdmin.StreamMessages> = emptyList(),
    var entries: List<SystemGetKeyValueEntriesData.NatsAdmin.KeyValueEntries> = emptyList(),
    var messageLimit: String = "50", var subjectFilter: String = "", var fromSeq: String = "",
    var toSeq: String = "", var dataSearch: String = "", var expandedMessage: String = "",
    var keyFilter: String = "", var valueSearch: String = "", var expandedKey: String = "",
    var draftKey: String = "", var draftValue: String = "", var valueMode: String = "text",
    var operationKind: String = "", var operationResource: String = "", var operationTarget: String = "",
    var operationRequest: Long = 0, var operationAttempt: Long = 0, var operationCompleted: Boolean = false,
    var operationNotice: String = "",
    val feedback: SystemFeedback = SystemFeedback(),
    val consumerFeedback: SystemFeedback = SystemFeedback(),
    val messageFeedback: SystemFeedback = SystemFeedback(),
    val entryFeedback: SystemFeedback = SystemFeedback(),
    var noticeSequence: Int = 0,
) {
    val tabs get() = listOf(ViewTab("overview", "Overview"), ViewTab("streams", "Streams"), ViewTab("kv", "Key Values"), ViewTab("connections", "Connections"), ViewTab("routes", "Routes"))
    val streams get() = overview?.jetStreamStreams.orEmpty().filter { it.name.contains(streamFilter, ignoreCase = true) }
    val buckets get() = overview?.keyValueStores.orEmpty().filter { it.bucket.contains(bucketFilter, ignoreCase = true) }
    val connections get() = overview?.connections?.connections.orEmpty().filter { connection ->
        listOf(connection.name, connection.ip, connection.lang).any { it.orEmpty().contains(connectionFilter, ignoreCase = true) }
    }
    val selectedStream get() = if (detail == "stream") overview?.jetStreamStreams?.find { it.name == streamName } else null
    val selectedBucket get() = if (detail == "bucket") overview?.keyValueStores?.find { it.bucket == bucketName } else null
    val streamOpenRequest get() = if (selectedStream == null) "" else "stream-$streamRequest"
    val bucketOpenRequest get() = if (selectedBucket == null) "" else "bucket-$bucketRequest"
    val operationOpenRequest get() = if (operationKind.isBlank()) "" else "operation-$operationRequest-$operationAttempt"
    val operationTitle get() = when (operationKind) {
        "deleteMessage" -> "Delete message #$operationTarget?"
        "purgeStream" -> "Purge stream?"
        "purgeStreamDeletes" -> "Purge stream tombstones?"
        "deleteKey" -> "Delete key?"
        "purgeBucketDeletes" -> "Purge key-value tombstones?"
        else -> "Confirm NATS operation"
    }
    val operationDescription get() = when (operationKind) {
        "deleteMessage" -> "Delete message #$operationTarget from $operationResource."
        "purgeStream" -> "Remove every message from $operationResource. This cannot be undone."
        "purgeStreamDeletes" -> "Remove DEL/PURGE tombstone markers from $operationResource."
        "deleteKey" -> "Delete key \"$operationTarget\" from $operationResource."
        "purgeBucketDeletes" -> "Remove deleted/tombstoned entries from $operationResource."
        else -> ""
    }
    val operationReturnFocus get() = if (operationKind in setOf("deleteKey", "purgeBucketDeletes")) "[data-nats-bucket-panel] [data-bml-ref=close]" else "[data-nats-stream-panel] [data-bml-ref=close]"
    val uptime: Pair<String, String> get() {
        val raw = overview?.serverInfo?.uptime ?: return "—" to ""
        val match = Regex("""^(\d+d)?(\d+h)?(\d+m)?(\d+s)?$""").matchEntire(raw) ?: return raw to ""
        val units = match.groupValues.drop(1).filter(String::isNotBlank)
        return units.take(2).joinToString(" ").ifBlank { raw } to units.drop(2).joinToString(" ")
    }

    private fun authorize() = require(admin) { "Administrator access is required." }
    private suspend fun overviewData(ctx: RenderContext) {
        overview = ctx.gql.execute(SystemGetNatsAdminOverview, Unit).natsAdmin; refreshes++
    }
    private suspend fun consumerData(ctx: RenderContext) {
        consumers = ctx.gql.execute(SystemGetJetStreamConsumers, SystemGetJetStreamConsumers.Variables(streamName)).natsAdmin.jetStreamConsumers
    }
    private suspend fun messageData(ctx: RenderContext) {
        val limit = messageLimit.toIntOrNull()
        require(limit != null && limit in 1..1000) { "Message limit must be between 1 and 1000." }
        fun sequence(value: String): Long? {
            if (value.isBlank()) return null
            return value.toLongOrNull()?.takeIf { it >= 0 } ?: error("Enter a non-negative 64-bit sequence.")
        }
        val first = sequence(fromSeq); val last = sequence(toSeq)
        require(first == null || last == null || first <= last) { "From sequence cannot exceed to sequence." }
        messages = ctx.gql.execute(SystemGetStreamMessages, SystemGetStreamMessages.Variables(streamName, limit, subjectFilter.takeIf(String::isNotBlank), first, last, dataSearch.takeIf(String::isNotBlank))).natsAdmin.streamMessages
        if (messages.none { it.sequence.toString() == expandedMessage }) expandedMessage = ""
    }
    private suspend fun entryData(ctx: RenderContext) {
        entries = ctx.gql.execute(SystemGetKeyValueEntries, SystemGetKeyValueEntries.Variables(bucketName, keyFilter.takeIf(String::isNotBlank), valueSearch.takeIf(String::isNotBlank))).natsAdmin.keyValueEntries
        if (entries.none { it.key == expandedKey }) expandedKey = ""
    }
    private fun messageDraft(limit: String, subject: String, from: String, to: String, search: String) {
        messageLimit = limit; subjectFilter = subject; fromSeq = from; toSeq = to; dataSearch = search
    }
    private fun entryDraft(key: String, value: String, filter: String, search: String) {
        draftKey = key; draftValue = value; keyFilter = filter; valueSearch = search
    }
    private fun prepare(kind: String, resource: String, target: String) {
        operationKind = kind; operationResource = resource; operationTarget = target
        operationRequest++; operationAttempt = 0; operationCompleted = false; operationNotice = ""
    }

    suspend fun refresh(ctx: RenderContext) = feedback.perform { authorize(); operationNotice = ""; overviewData(ctx) }
    fun chooseTab(value: String) { require(tabs.any { it.value == value }); tab = value }
    fun filterStreams(value: String) { streamFilter = value }
    fun filterBuckets(value: String) { bucketFilter = value }
    fun filterConnections(value: String) { connectionFilter = value }

    suspend fun openStream(name: String, ctx: RenderContext) = feedback.perform {
        authorize(); require(overview?.jetStreamStreams?.any { it.name == name } == true) { "Stream not found. Refresh the overview." }
        detail = "stream"; streamName = name; streamRequest++; operationKind = ""; operationNotice = ""
        messageDraft("50", "", "", "", ""); expandedMessage = ""; consumers = emptyList(); messages = emptyList()
        consumerFeedback.perform { consumerData(ctx) }
        messageFeedback.perform { messageData(ctx) }
    }
    suspend fun reloadStream(limit: String, subject: String, from: String, to: String, search: String, ctx: RenderContext) = feedback.perform {
        authorize(); messageDraft(limit, subject, from, to, search); streamRequest++
        consumerFeedback.perform { consumerData(ctx) }; messageFeedback.perform { messageData(ctx) }
    }
    suspend fun loadMessages(limit: String, subject: String, from: String, to: String, search: String, ctx: RenderContext) = messageFeedback.perform {
        authorize(); messageDraft(limit, subject, from, to, search); streamRequest++; messageData(ctx)
    }
    fun toggleMessage(sequence: String, limit: String, subject: String, from: String, to: String, search: String) {
        messageDraft(limit, subject, from, to, search)
        expandedMessage = if (expandedMessage == sequence) "" else sequence; streamRequest++
    }
    suspend fun prepareStreamOperation(kind: String, sequence: String, limit: String, subject: String, from: String, to: String, search: String) = feedback.perform {
        authorize(); require(kind in setOf("deleteMessage", "purgeStream", "purgeStreamDeletes"))
        messageDraft(limit, subject, from, to, search); prepare(kind, streamName, sequence)
    }
    suspend fun openBucket(name: String, ctx: RenderContext) = feedback.perform {
        authorize(); require(overview?.keyValueStores?.any { it.bucket == name } == true) { "Bucket not found. Refresh the overview." }
        detail = "bucket"; bucketName = name; bucketRequest++; operationKind = ""; operationNotice = ""
        entryDraft("", "", "", ""); expandedKey = ""; entries = emptyList(); valueMode = "text"
        entryFeedback.perform { entryData(ctx) }
    }
    suspend fun loadEntries(filter: String, search: String, key: String, value: String, ctx: RenderContext) = entryFeedback.perform {
        authorize(); entryDraft(key, value, filter, search); bucketRequest++; entryData(ctx)
    }
    fun toggleEntry(key: String, draft: String, value: String, filter: String, search: String) {
        entryDraft(draft, value, filter, search); expandedKey = if (expandedKey == key) "" else key; bucketRequest++
    }
    fun setValueMode(mode: String, key: String, value: String, filter: String, search: String) {
        require(mode in setOf("text", "json")); valueMode = mode; entryDraft(key, value, filter, search); bucketRequest++
    }
    suspend fun putEntry(key: String, value: String, filter: String, search: String, ctx: RenderContext) = feedback.perform {
        authorize(); entryDraft(key, value, filter, search); bucketRequest++; operationNotice = ""
        require(bucketName.isNotBlank()) { "Choose a bucket." }; require(key.isNotBlank()) { "Enter a key." }
        if (valueMode == "json") Json.parseToJsonElement(value)
        val stored = ctx.gql.execute(SystemPutKvEntry, SystemPutKvEntry.Variables(bucketName, key, value)).natsAdmin.putKeyValueEntry
        check(stored.key == key) { "The stored key did not match the request." }
        operationNotice = "Stored $key."; noticeSequence++; feedback.message = operationNotice
        draftKey = ""; draftValue = if (valueMode == "json") "{}" else ""
        entryFeedback.perform { entryData(ctx) }; overviewData(ctx)
    }
    suspend fun prepareBucketOperation(kind: String, target: String, key: String, value: String, filter: String, search: String) = feedback.perform {
        authorize(); require(kind in setOf("deleteKey", "purgeBucketDeletes"))
        entryDraft(key, value, filter, search); prepare(kind, bucketName, target)
    }
    suspend fun confirmOperation(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        operationAttempt++; operationCompleted = false; operationNotice = ""; authorize()
        require(confirmed) { "Confirm this operation." }
        require(operationResource.isNotBlank()) { "Choose a NATS resource." }
        when (operationKind) {
            "deleteMessage" -> {
                val sequence = operationTarget.toLong()
                check(ctx.gql.execute(SystemDeleteStreamMessage, SystemDeleteStreamMessage.Variables(operationResource, sequence)).natsAdmin.deleteStreamMessage == true) { "NATS did not delete this message." }
                operationNotice = "Deleted message #$operationTarget."
            }
            "purgeStream" -> {
                check(ctx.gql.execute(SystemPurgeStream, SystemPurgeStream.Variables(operationResource)).natsAdmin.purgeStream == true) { "NATS did not purge the stream." }
                operationNotice = "Stream $operationResource purged."
            }
            "purgeStreamDeletes" -> {
                val removed = ctx.gql.execute(SystemPurgeStreamDeletes, SystemPurgeStreamDeletes.Variables(operationResource)).natsAdmin.purgeStreamDeletes
                operationNotice = if (removed < 0) "Key-value tombstones purged from $operationResource." else "Removed $removed tombstones from $operationResource."
            }
            "deleteKey" -> {
                check(ctx.gql.execute(SystemDeleteKvEntry, SystemDeleteKvEntry.Variables(operationResource, operationTarget)).natsAdmin.deleteKeyValueEntry == true) { "NATS did not delete this key." }
                operationNotice = "Deleted $operationTarget."
            }
            "purgeBucketDeletes" -> {
                check(ctx.gql.execute(SystemPurgeKvDeletes, SystemPurgeKvDeletes.Variables(operationResource)).natsAdmin.purgeKeyValueDeletes == true) { "NATS did not purge key-value tombstones." }
                operationNotice = "Tombstones purged from $operationResource."
            }
            else -> error("Choose a supported NATS operation.")
        }
        operationCompleted = true; noticeSequence++; feedback.message = operationNotice
        if (operationKind in setOf("deleteKey", "purgeBucketDeletes")) {
            bucketRequest++; entryFeedback.perform { entryData(ctx) }
        } else {
            streamRequest++; messageFeedback.perform { messageData(ctx) }; consumerFeedback.perform { consumerData(ctx) }
        }
        overviewData(ctx)
    }
}

suspend fun natsConsole(): NatsConsoleModel {
    val ctx = currentRenderContext()
    return NatsConsoleModel(systemAdministrator(ctx)).also { if (it.admin) it.refresh(ctx) }
}
