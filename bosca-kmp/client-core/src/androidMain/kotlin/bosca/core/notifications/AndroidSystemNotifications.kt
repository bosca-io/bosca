package bosca.core.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.LocusId
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** Android system-notification presentation derived from Bosca notification types. */
internal object AndroidSystemNotifications {
    const val FALLBACK_CHANNEL_ID = "bosca_notifications"

    private const val CHANNEL_PREFERENCES = "bosca.notification.channels"
    private const val CHANNEL_IDS_KEY = "ids"

    fun synchronizeChannels(context: Context, types: List<NotificationTypeDefinition>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.notificationManager()
        val channelIds = types.mapNotNull { type ->
            type.key.normalized()?.let { channelId ->
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        type.name.normalized() ?: channelId,
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = type.description.normalized()
                    },
                )
                channelId
            }
        }.toSet()
        val preferences = context.getSharedPreferences(CHANNEL_PREFERENCES, Context.MODE_PRIVATE)
        val previousIds = preferences.getStringSet(CHANNEL_IDS_KEY, emptySet()).orEmpty()
        (previousIds - channelIds).forEach(manager::deleteNotificationChannel)
        preferences.edit().putStringSet(CHANNEL_IDS_KEY, channelIds).apply()
        createFallbackChannel(context, manager)
    }

    fun show(context: Context, message: PushMessage, accessToken: String?) {
        if (message.data["silent"].isTruthy()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.notificationManager()
        createFallbackChannel(context, manager)
        val notificationType = message.data[NOTIFICATION_TYPE_KEY].normalized()
        val requestedChannel = message.data[ANDROID_NOTIFICATION_CHANNEL_ID_KEY].normalized()
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sequenceOf(requestedChannel, notificationType)
                .filterNotNull()
                .firstOrNull { manager.getNotificationChannel(it) != null }
                ?: FALLBACK_CHANNEL_ID
        } else {
            null
        }
        val title = message.title.normalized() ?: context.applicationLabel()
        val body = message.body.normalized()
        val actionSet = message.actionSet()
        val defaultAction = actionSet?.defaultAction
        val actionUrl = defaultAction?.url.normalized()
            ?: message.data[ANDROID_NOTIFICATION_LINK_KEY].normalized()
        val replacementTag = message.data[ANDROID_NOTIFICATION_TAG_KEY].normalized()
            ?: message.data["android_tag"].normalized()
        val notificationId = (replacementTag
            ?: message.id.normalized()
            ?: message.data["id"].normalized()
            ?: "$title:$body:$actionUrl").hashCode()
        val pendingIntent = actionPendingIntent(context, notificationId, defaultAction, actionUrl)
        val richContent = message.richContent()
        val conversation = richContent?.conversation
        if (conversation != null) {
            showConversation(
                context = context,
                manager = manager,
                channelId = channelId,
                title = title,
                body = body,
                conversation = conversation,
                attachment = richContent.attachments.firstOrNull { it.type == PushAttachmentType.IMAGE },
                accessToken = accessToken,
                pendingIntent = pendingIntent,
                actionSet = actionSet,
                actionUrl = actionUrl,
                notificationType = notificationType,
                sound = message.data[ANDROID_NOTIFICATION_SOUND_KEY].normalized(),
            )
            return
        }
        val builder = notificationBuilder(context, channelId)
            .setSmallIcon(context.notificationIcon())
            .setContentTitle(title)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        body?.let {
            builder.setContentText(it)
            builder.setStyle(Notification.BigTextStyle().bigText(it))
        }
        message.presentationImage()
            ?.let { NotificationImageStore.fetch(context, it, accessToken, actionUrl) }
            ?.let { NotificationImageStore.bitmap(context, it) }
            ?.let { image ->
                builder.setStyle(
                    Notification.BigPictureStyle()
                        .bigPicture(image)
                        .setSummaryText(body),
                )
            }
        message.presentationBadge()?.let(builder::setNumber)
        applyLegacySound(context, builder, message.data[ANDROID_NOTIFICATION_SOUND_KEY].normalized())
        pendingIntent?.let(builder::setContentIntent)
        actionSet?.actions.orEmpty().forEachIndexed { index, action ->
            val label = action.label.normalized() ?: return@forEachIndexed
            val actionIntent = actionPendingIntent(
                context,
                notificationId * 31 + index + 1,
                action,
                action.url.normalized(),
            ) ?: return@forEachIndexed
            builder.addAction(Notification.Action.Builder(0, label, actionIntent).build())
        }
        val tag = replacementTag ?: notificationType
        manager.notify(tag, notificationId, builder.build())
    }

    private fun showConversation(
        context: Context,
        manager: NotificationManager,
        channelId: String?,
        title: String,
        body: String?,
        conversation: PushConversation,
        attachment: PushAttachment?,
        accessToken: String?,
        pendingIntent: PendingIntent?,
        actionSet: PushNotificationActionSet?,
        actionUrl: String?,
        notificationType: String?,
        sound: String?,
    ) {
        val visibleConversation = conversation.body.normalized()?.let { conversation }
            ?: conversation.copy(body = body.normalized().orEmpty())
        val attachmentUri = attachment?.let { NotificationImageStore.fetch(context, it, accessToken, actionUrl) }
        val messages = ConversationNotificationStore.append(
            context,
            visibleConversation,
            attachmentUri?.toString(),
            attachment?.mediaType ?: attachmentUri?.let(context.contentResolver::getType),
        )
        val applicationName = context.applicationLabel()
        @Suppress("DEPRECATION")
        val style = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Notification.MessagingStyle(Person.Builder().setName(applicationName).build())
        } else {
            Notification.MessagingStyle(applicationName)
        }
        if (conversation.groupConversation) style.conversationTitle = conversation.title ?: title
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            style.isGroupConversation = conversation.groupConversation
        }
        messages.forEach { stored ->
            val senderName = stored.senderName.normalized() ?: conversation.title.normalized() ?: title
            @Suppress("DEPRECATION")
            val nativeMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Notification.MessagingStyle.Message(
                    stored.body,
                    stored.sentAtEpochMilliseconds,
                    Person.Builder()
                        .setKey(stored.senderId.normalized())
                        .setName(senderName)
                        .build(),
                )
            } else {
                Notification.MessagingStyle.Message(
                    stored.body,
                    stored.sentAtEpochMilliseconds,
                    senderName,
                )
            }
            stored.attachmentUri?.let { uri ->
                nativeMessage.setData(
                    stored.attachmentMediaType.normalized() ?: "image/*",
                    Uri.parse(uri),
                )
            }
            style.addMessage(nativeMessage)
        }

        val notificationId = conversation.id.hashCode()
        val builder = notificationBuilder(context, channelId)
            .setSmallIcon(context.notificationIcon())
            .setContentTitle(conversation.title.normalized() ?: title)
            .setContentText(visibleConversation.body)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setStyle(style)
        applyLegacySound(context, builder, sound)
        pendingIntent?.let(builder::setContentIntent)
        actionSet?.actions.orEmpty().forEachIndexed { index, action ->
            val label = action.label.normalized() ?: return@forEachIndexed
            val actionIntent = actionPendingIntent(
                context,
                notificationId * 31 + index + 1,
                action,
                action.url.normalized(),
            ) ?: return@forEachIndexed
            builder.addAction(Notification.Action.Builder(0, label, actionIntent).build())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            installConversationShortcut(
                context,
                builder,
                conversation,
                actionSet?.defaultAction,
                actionUrl,
            )
        }
        manager.notify(notificationType ?: "bosca-conversation", notificationId, builder.build())
    }

    private fun installConversationShortcut(
        context: Context,
        builder: Notification.Builder,
        conversation: PushConversation,
        action: PushNotificationAction?,
        actionUrl: String?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = actionIntent(context, action, actionUrl) ?: return
        val shortcutId = "bosca-conversation-${conversation.id.hashCode().toUInt()}"
        val sender = Person.Builder()
            .setKey(conversation.senderId.normalized())
            .setName(conversation.senderName.normalized() ?: conversation.title.normalized() ?: context.applicationLabel())
            .build()
        val shortcut = ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel((conversation.title.normalized() ?: sender.name.toString()).take(40))
            .setLongLived(true)
            .setPersons(arrayOf(sender))
            .setIntent(intent)
            .build()
        context.getSystemService(ShortcutManager::class.java).pushDynamicShortcut(shortcut)
        builder.setShortcutId(shortcutId)
        builder.setLocusId(LocusId(shortcutId))
    }

    @Suppress("DEPRECATION")
    private fun applyLegacySound(
        context: Context,
        builder: Notification.Builder,
        messageSound: String?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || messageSound == null) return
        if (messageSound == "default") {
            builder.setDefaults(Notification.DEFAULT_SOUND)
            return
        }
        val resourceName = messageSound.substringBeforeLast('.')
        val resource = context.resources.getIdentifier(resourceName, "raw", context.packageName)
        if (resource != 0) {
            builder.setSound(Uri.parse("android.resource://${context.packageName}/$resource"))
        }
    }

    private fun createFallbackChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                FALLBACK_CHANNEL_ID,
                context.applicationLabel(),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun actionPendingIntent(
        context: Context,
        requestCode: Int,
        action: PushNotificationAction?,
        url: String?,
    ): PendingIntent? {
        val intent = actionIntent(context, action, url) ?: return null
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(
        context: Context,
        action: PushNotificationAction?,
        url: String?,
    ): Intent? {
        val intent = url?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        intent.flags = intent.flags or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        action?.let { selected ->
            intent.putExtra(NOTIFICATION_SELECTED_ACTION_ID_KEY, selected.id)
            selected.data?.forEach { (key, value) -> intent.putExtra(key, value) }
        }
        return intent
    }

    private fun Context.applicationLabel(): String =
        applicationInfo.loadLabel(packageManager).toString().normalized() ?: packageName

    private fun Context.notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Suppress("DEPRECATION")
    private fun notificationBuilder(context: Context, channelId: String?): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, checkNotNull(channelId))
        } else {
            Notification.Builder(context)
        }

    @Suppress("DEPRECATION")
    private fun Context.notificationIcon(): Int {
        val application = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val configured = application.metaData
            ?.getInt("com.google.firebase.messaging.default_notification_icon", 0)
            ?: 0
        return configured.takeIf { it != 0 }
            ?: applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.stat_notify_more
    }

    private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun String?.isTruthy(): Boolean =
        this?.equals("true", ignoreCase = true) == true || this == "1"
}
