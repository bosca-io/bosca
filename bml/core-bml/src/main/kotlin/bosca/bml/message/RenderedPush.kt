package bosca.bml.message

/** Visible push copy plus the producer-owned, template-selected delivery envelope. */
data class RenderedPush(
    val title: String,
    val body: String,
    val options: BmlPushOptions? = null,
)

/** Provider-neutral push options available to a BML message template. */
data class BmlPushOptions(
    val imageUrl: String? = null,
    val defaultAction: BmlPushAction? = null,
    val actions: List<BmlPushAction> = emptyList(),
    val priority: String? = null,
    val sound: String? = null,
    val badge: Int? = null,
    val ttl: Long? = null,
    val androidChannelId: String? = null,
    val androidTag: String? = null,
    val collapseKey: String? = null,
    val threadId: String? = null,
    val category: String? = null,
    val interruptionLevel: String? = null,
    val relevanceScore: Double? = null,
    val mutableContent: Boolean? = null,
    val contentAvailable: Boolean? = null,
    val data: Map<String, String>? = null,
    val richContent: BmlPushRichContent? = null,
) {
    /**
     * Selects producer-supplied actions in template order and overlays their localized labels.
     * Destinations and routing data remain producer-owned and a missing action is a render error.
     */
    fun selectActions(selections: List<BmlPushActionSelection>): BmlPushOptions {
        val available = linkedMapOf<String, BmlPushAction>()
        listOfNotNull(defaultAction).plus(actions).forEach { action ->
            require(action.id.isNotBlank()) { "push action id must not be blank" }
            require(available.put(action.id, action) == null) {
                "push action '${action.id}' was supplied more than once"
            }
        }
        require(selections.map { it.id }.distinct().size == selections.size) {
            "a BML push template selected the same action more than once"
        }
        require(selections.count { it.isDefault } <= 1) {
            "a BML push template selected more than one default action"
        }
        fun selected(selection: BmlPushActionSelection): BmlPushAction {
            val action = requireNotNull(available[selection.id]) {
                "BML push action '${selection.id}' was not supplied by the message producer"
            }
            return action.copy(label = selection.label)
        }
        return copy(
            defaultAction = selections.singleOrNull { it.isDefault }?.let(::selected),
            actions = selections.filterNot { it.isDefault }.map(::selected),
        )
    }

    /**
     * Applies the rich-presentation capabilities declared by a template's push tags. Producers
     * own the content itself; a template only selects which concepts its presentation supports.
     */
    fun selectRichContent(
        image: Boolean,
        attachments: Boolean,
        conversation: Boolean,
    ): BmlPushOptions {
        val selectedAttachments = if (attachments) richContent?.attachments.orEmpty() else emptyList()
        val selectedConversation = if (conversation) richContent?.conversation else null
        val selectedRichContent = if (selectedAttachments.isNotEmpty() || selectedConversation != null) {
            BmlPushRichContent(selectedAttachments, selectedConversation)
        } else {
            null
        }
        val selectedImage = if (image) {
            imageUrl ?: richContent?.attachments?.firstOrNull { it.type == BmlPushAttachmentType.IMAGE }?.url
        } else {
            null
        }
        return copy(imageUrl = selectedImage, richContent = selectedRichContent)
    }
}

/** Producer-owned notification action available to a BML push template. */
data class BmlPushAction(
    val id: String,
    val label: String? = null,
    val url: String? = null,
    val destructive: Boolean = false,
    val data: Map<String, String>? = null,
)

/** Localized action presentation selected declaratively by a BML `<action>` tag. */
data class BmlPushActionSelection(
    val id: String,
    val label: String,
    val isDefault: Boolean = false,
)

/** Structured content for rich notification presentation. */
data class BmlPushRichContent(
    val attachments: List<BmlPushAttachment> = emptyList(),
    val conversation: BmlPushConversation? = null,
)

/** A remote media attachment; providers receive its URL rather than inline bytes. */
data class BmlPushAttachment(
    val id: String? = null,
    val url: String,
    val type: BmlPushAttachmentType = BmlPushAttachmentType.IMAGE,
    val mediaType: String? = null,
    val altText: String? = null,
)

/** Media kinds available to rich-push clients. */
enum class BmlPushAttachmentType {
    IMAGE,
    VIDEO,
    AUDIO,
}

/** The current incoming message; notification clients own history and grouping. */
data class BmlPushConversation(
    val id: String,
    val title: String? = null,
    val messageId: String,
    val senderId: String? = null,
    val senderName: String? = null,
    val senderImageUrl: String? = null,
    val body: String,
    val sentAtEpochMilliseconds: Long? = null,
    val groupConversation: Boolean = false,
)
