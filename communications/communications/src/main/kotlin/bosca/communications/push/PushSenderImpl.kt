package bosca.communications.push

import bosca.communications.model.PushOptions
import bosca.communications.model.PushAttachmentType
import bosca.communications.model.PushActionSet
import bosca.communications.model.PushRichContent
import bosca.configuration.service.ConfigurationService
import bosca.devices.model.PushProvider
import bosca.server.http.await
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger

internal fun isPermanentlyInvalidFcmToken(
    code: MessagingErrorCode?,
    payloadWasAccepted: Boolean,
): Boolean = code == MessagingErrorCode.UNREGISTERED ||
    code == MessagingErrorCode.INVALID_ARGUMENT && payloadWasAccepted

internal fun isPermanentlyInvalidApnsToken(
    statusCode: Int,
    reason: String?,
): Boolean = statusCode == 410 ||
    reason == "Unregistered"

private val APNS_TOKEN_REJECTIONS = setOf("BadDeviceToken", "DeviceTokenNotForTopic")

/** Reserved provider data key containing a serialized [PushRichContent] envelope. */
const val RICH_PUSH_DATA_KEY = "bosca_rich_push_v1"

/** Reserved provider data key containing a serialized [PushActionSet] envelope. */
const val PUSH_ACTIONS_DATA_KEY = "bosca_push_actions_v1"

/** Reserved Android client-render data keys for presentation fields omitted from data-only FCM messages. */
const val ANDROID_NOTIFICATION_IMAGE_DATA_KEY = "bosca_android_notification_image"
const val ANDROID_NOTIFICATION_BADGE_DATA_KEY = "bosca_android_notification_badge"

internal fun PushOptions.notificationImageUrl(): String? = imageUrl
    ?: richContent?.attachments?.firstOrNull { it.type == PushAttachmentType.IMAGE }?.url

internal fun PushOptions.richContentPayload(json: Json): String? = richContent?.let {
    json.encodeToString(PushRichContent.serializer(), it)
}

internal fun PushOptions.actionPayload(json: Json): String? {
    val actionSet = PushActionSet(defaultAction, actions)
        .takeIf { it.defaultAction != null || it.actions.isNotEmpty() }
        ?: return null
    val actionIds = listOfNotNull(actionSet.defaultAction) + actionSet.actions
    require(actionIds.all { it.id.isNotBlank() }) { "Push action IDs must not be blank" }
    require(actionIds.map { it.id }.distinct().size == actionIds.size) {
        "Push action IDs must be unique across the default and secondary actions"
    }
    return json.encodeToString(PushActionSet.serializer(), actionSet)
}

internal fun PushOptions.providerData(
    json: Json,
    renderOnAndroidClient: Boolean,
    title: String,
    body: String,
): Map<String, String> = buildMap {
    data?.let { putAll(it) }
    actionPayload(json)?.let { put(PUSH_ACTIONS_DATA_KEY, it) }
    richContentPayload(json)?.let { put(RICH_PUSH_DATA_KEY, it) }
    if (renderOnAndroidClient) {
        put("title", title)
        put("body", body)
        androidChannelId?.let { put(ANDROID_CHANNEL_DATA_KEY, it) }
        androidTag?.let { put(ANDROID_TAG_DATA_KEY, it) }
        sound?.let { put(ANDROID_SOUND_DATA_KEY, it) }
        notificationImageUrl()?.let { put(ANDROID_NOTIFICATION_IMAGE_DATA_KEY, it) }
        badge?.let { put(ANDROID_NOTIFICATION_BADGE_DATA_KEY, it.toString()) }
    }
}

internal fun requiresAndroidClientRendering(
    platform: PushPlatform,
    options: PushOptions?,
): Boolean = platform == PushPlatform.ANDROID && options != null && (
    options.defaultAction != null ||
        options.actions.isNotEmpty() ||
        options.richContent != null
)

/**
 * Delivers push notifications via Firebase Cloud Messaging and Apple Push Notification service.
 *
 * Calls route strictly by the service that issued each token.
 */
class PushSenderImpl(
    private val json: Json,
    private val configurationService: ConfigurationService,
) : PushSender {

    private val logger = LoggerFactory.getLogger(PushSenderImpl::class.java)
    private val firebaseApps = mutableMapOf<String, FirebaseApp>()
    private val firebaseAppsLock = Mutex()

    private val httpClient = OkHttpClient()

    private var cachedApnsJwt: String? = null
    private var cachedApnsJwtTimestamp: Long = 0
    private var cachedApnsCredentials: ApnsJwtCredentials? = null
    private val apnsJwtLock = Mutex()
    private val apnsSemaphore = Semaphore(10)

    override suspend fun send(
        tokens: List<String>,
        subject: String,
        content: String,
        platform: PushPlatform,
        provider: PushProvider,
        options: PushOptions?,
    ): PushSendResult {
        val pushConfiguration = configurationService.getPushConfiguration(json)
        if (!pushConfiguration.enabled) {
            logger.info("Push notifications are disabled.")
            return PushSendResult()
        }
        return when (provider) {
            PushProvider.FCM -> sendToFirebase(pushConfiguration, tokens, subject, content, platform, options)
            PushProvider.APNS -> {
                if (platform != PushPlatform.IOS) {
                    logger.warn("APNs tokens cannot be delivered to {} devices", platform)
                    PushSendResult(failureCount = tokens.size)
                } else {
                    sendToAPNs(pushConfiguration, tokens, subject, content, options)
                }
            }
        }
    }

    private suspend fun getFirebaseMessaging(configuration: FcmConfiguration?): FirebaseMessaging? {
        val fcmConfiguration = configuration ?: return null
        val serviceAccountJson = fcmConfiguration.serviceAccountJson?.takeIf { it.isNotBlank() }
        val fingerprint = credentialFingerprint(serviceAccountJson ?: APPLICATION_DEFAULT_CREDENTIALS)
        val app = firebaseAppsLock.withLock {
            firebaseApps[fingerprint] ?: withContext(Dispatchers.IO) {
                val credentials = if (serviceAccountJson == null) {
                    GoogleCredentials.getApplicationDefault()
                } else {
                    GoogleCredentials.fromStream(ByteArrayInputStream(serviceAccountJson.toByteArray()))
                }
                val options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build()
                val appName = "$FIREBASE_APP_PREFIX-$fingerprint"
                FirebaseApp.getApps().firstOrNull { it.name == appName }
                    ?: FirebaseApp.initializeApp(options, appName)
            }.also { firebaseApps[fingerprint] = it }
        }
        return FirebaseMessaging.getInstance(app)
    }

    private suspend fun sendToFirebase(
        pushConfiguration: PushConfiguration,
        tokens: List<String>,
        subject: String,
        content: String,
        platform: PushPlatform,
        options: PushOptions?,
    ): PushSendResult {
        val messaging = getFirebaseMessaging(pushConfiguration.fcm)
        if (messaging == null) {
            logger.warn("FCM not initialized, skipping FCM send.")
            return PushSendResult(failureCount = tokens.size)
        }
        val notificationImageUrl = options?.notificationImageUrl()
        val notification = Notification.builder()
            .setTitle(subject)
            .setBody(content)
            .apply { notificationImageUrl?.let { setImage(it) } }
            .build()

        val renderOnAndroidClient = requiresAndroidClientRendering(platform, options)
        var androidConfig: AndroidConfig? = null
        var apnsConfig: ApnsConfig? = null
        var dataMap: Map<String, String>? = null

        if (options != null) {
            if (platform == PushPlatform.ANDROID) {
                val androidNotif = AndroidNotification.builder()
                options.androidChannelId?.let { androidNotif.setChannelId(it) }
                options.androidTag?.let { androidNotif.setTag(it) }
                options.sound?.let { androidNotif.setSound(it) }
                options.badge?.let { androidNotif.setNotificationCount(it) }
                notificationImageUrl?.let { androidNotif.setImage(it) }
                val androidBuilder = AndroidConfig.builder()
                if (!renderOnAndroidClient) androidBuilder.setNotification(androidNotif.build())
                options.collapseKey?.let { androidBuilder.setCollapseKey(it) }
                options.ttl?.let { androidBuilder.setTtl(it * 1000) }
                if (renderOnAndroidClient) {
                    androidBuilder.setPriority(AndroidConfig.Priority.HIGH)
                } else options.priority?.let {
                    androidBuilder.setPriority(
                        if (it == "HIGH") AndroidConfig.Priority.HIGH else AndroidConfig.Priority.NORMAL
                    )
                }
                androidConfig = androidBuilder.build()
            }

            if (platform == PushPlatform.IOS) {
                val apsBuilder = Aps.builder()
                options.sound?.let { apsBuilder.setSound(it) }
                options.badge?.let { apsBuilder.setBadge(it) }
                options.threadId?.let { apsBuilder.setThreadId(it) }
                options.category?.let { apsBuilder.setCategory(it) }
                if (options.mutableContent == true || options.richContent != null || notificationImageUrl != null) {
                    apsBuilder.setMutableContent(true)
                }
                if (options.contentAvailable == true) apsBuilder.setContentAvailable(true)
                options.interruptionLevel?.let {
                    apsBuilder.putCustomData("interruption-level", it)
                }
                options.relevanceScore?.let {
                    requireValidRelevanceScore(it)
                    apsBuilder.putCustomData("relevance-score", it)
                }
                val apnsBuilder = ApnsConfig.builder()
                    .setAps(apsBuilder.build())
                notificationImageUrl?.let {
                    apnsBuilder.setFcmOptions(ApnsFcmOptions.builder().setImage(it).build())
                }
                options.priority?.let {
                    apnsBuilder.putHeader("apns-priority", if (it == "HIGH") "10" else "5")
                }
                options.collapseKey?.let {
                    apnsBuilder.putHeader("apns-collapse-id", it)
                }
                options.ttl?.let {
                    apnsBuilder.putHeader("apns-expiration", (System.currentTimeMillis() / 1000 + it).toString())
                }
                apnsConfig = apnsBuilder.build()
            }

            dataMap = options.providerData(json, renderOnAndroidClient, subject, content)
        }

        requireProviderPayloadWithinLimit(
            provider = "FCM",
            payload = buildJsonObject {
                if (!renderOnAndroidClient) {
                    putJsonObject("notification") {
                        put("title", subject)
                        put("body", content)
                        notificationImageUrl?.let { put("image", it) }
                    }
                }
                dataMap?.let { values ->
                    putJsonObject("data") { values.forEach { (key, value) -> put(key, value) } }
                }
            }.toString(),
        )

        var totalSuccess = 0
        var totalFailure = 0
        val invalidTokens = mutableSetOf<String>()
        for (chunk in tokens.chunked(500)) {
            val builder = MulticastMessage.builder()
                .addAllTokens(chunk)
            if (!renderOnAndroidClient) builder.setNotification(notification)
            androidConfig?.let { builder.setAndroidConfig(it) }
            apnsConfig?.let { builder.setApnsConfig(it) }
            dataMap?.let { builder.putAllData(it) }

            val response = messaging.sendEachForMulticast(builder.build())
            totalSuccess += response.successCount
            totalFailure += response.failureCount
            logger.info("Sent {} messages via FCM, {} successes, {} failures", chunk.size, response.successCount, response.failureCount)
            val payloadWasAccepted = response.successCount > 0
            response.responses.forEachIndexed { index, sendResponse ->
                if (!sendResponse.isSuccessful) {
                    val code = sendResponse.exception?.messagingErrorCode
                    if (isPermanentlyInvalidFcmToken(code, payloadWasAccepted)) {
                        val invalidToken = chunk[index]
                        invalidTokens += invalidToken
                        logger.warn("FCM token invalid/unregistered: {}", invalidToken)
                    }
                }
            }
        }
        return PushSendResult(
            successCount = totalSuccess,
            failureCount = totalFailure,
            invalidTokens = invalidTokens,
        )
    }

    private suspend fun sendToAPNs(
        pushConfiguration: PushConfiguration,
        tokens: List<String>,
        subject: String,
        content: String,
        options: PushOptions?,
    ): PushSendResult {
        val apns = pushConfiguration.apns?.takeIf { it.isConfigured } ?: run {
            logger.warn("APNs is not fully configured, skipping APNs send.")
            return PushSendResult(failureCount = tokens.size)
        }
        val privateKey = apns.privateKey ?: return PushSendResult(failureCount = tokens.size)
        val teamId = apns.teamId ?: return PushSendResult(failureCount = tokens.size)
        val keyId = apns.keyId ?: return PushSendResult(failureCount = tokens.size)
        val bundleId = apns.bundleId ?: return PushSendResult(failureCount = tokens.size)
        val jwt = getOrCreateApnsJwt(teamId, keyId, privateKey)
        val host = if (apns.sandbox) "api.sandbox.push.apple.com" else "api.push.apple.com"
        val notificationImageUrl = options?.notificationImageUrl()
        val payload = buildJsonObject {
            putJsonObject("aps") {
                putJsonObject("alert") {
                    put("title", subject)
                    put("body", content)
                }
                put("sound", options?.sound ?: "default")
                options?.badge?.let { put("badge", it) }
                options?.threadId?.let { put("thread-id", it) }
                options?.category?.let { put("category", it) }
                if (options?.mutableContent == true || options?.richContent != null || notificationImageUrl != null) {
                    put("mutable-content", 1)
                }
                if (options?.contentAvailable == true) put("content-available", 1)
                options?.interruptionLevel?.let { put("interruption-level", it) }
                options?.relevanceScore?.let {
                    requireValidRelevanceScore(it)
                    put("relevance-score", it)
                }
            }
            options?.data?.forEach { (k, v) -> put(k, v) }
            options?.actionPayload(json)?.let { put(PUSH_ACTIONS_DATA_KEY, it) }
            notificationImageUrl?.let { put("image_url", it) }
            options?.richContentPayload(json)?.let { put(RICH_PUSH_DATA_KEY, it) }
        }
        requireProviderPayloadWithinLimit("APNs", payload.toString())
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val rejections = Collections.synchronizedMap(mutableMapOf<String, Pair<Int, String?>>())
        coroutineScope {
            tokens.map { token ->
                async {
                    apnsSemaphore.withPermit {
                        try {
                            val requestBuilder = Request.Builder()
                                .url("https://$host/3/device/$token")
                                .post(payload.toString().toRequestBody(jsonMediaType))
                                .header("authorization", "Bearer $jwt")
                                .header("apns-topic", bundleId)
                                .header("apns-push-type", "alert")
                            options?.priority?.let {
                                requestBuilder.header("apns-priority", if (it == "HIGH") "10" else "5")
                            }
                            options?.collapseKey?.let {
                                requestBuilder.header("apns-collapse-id", it)
                            }
                            options?.ttl?.let {
                                requestBuilder.header("apns-expiration", (System.currentTimeMillis() / 1000 + it).toString())
                            }
                            val response = httpClient.newCall(requestBuilder.build()).await()
                            response.use { resp ->
                                if (resp.isSuccessful) {
                                    logger.info("Sent APNS message to device {}", token)
                                    successCount.incrementAndGet()
                                } else {
                                    val reason = resp.body.string()
                                        .takeIf { it.isNotBlank() }
                                        ?.let { body ->
                                            runCatching {
                                                Json.parseToJsonElement(body).jsonObject["reason"]?.jsonPrimitive?.contentOrNull
                                            }.getOrNull()
                                        }
                                    rejections[token] = resp.code to reason
                                    logger.warn(
                                        "Failed to send APNS message to device {}: {} {}",
                                        token,
                                        resp.code,
                                        reason ?: "",
                                    )
                                    failureCount.incrementAndGet()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("Error sending APNS message to device {}: {}", token, e.message)
                            failureCount.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }
        val invalidTokens = rejections.filterValues { (statusCode, reason) ->
            isPermanentlyInvalidApnsToken(statusCode, reason)
        }.keys
        if (rejections.values.any { (_, reason) ->
                reason != null && reason in APNS_TOKEN_REJECTIONS
            }) {
            logger.warn(
                "APNs reported topic/environment-sensitive token rejections; preserving those tokens",
            )
        }
        return PushSendResult(
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            invalidTokens = invalidTokens.toSet(),
        )
    }

    private suspend fun getOrCreateApnsJwt(teamId: String, keyId: String, privateKeyPem: String): String {
        val now = Instant.now().epochSecond
        val credentials = ApnsJwtCredentials(teamId, keyId, privateKeyPem)
        apnsJwtLock.withLock {
            val cached = cachedApnsJwt
            if (cached != null && cachedApnsCredentials == credentials && (now - cachedApnsJwtTimestamp) < 1200) {
                return cached
            }
            val jwt = createApnsJwt(teamId, keyId, privateKeyPem)
            cachedApnsJwt = jwt
            cachedApnsJwtTimestamp = now
            cachedApnsCredentials = credentials
            return jwt
        }
    }

    private fun credentialFingerprint(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray()),
            0,
            FINGERPRINT_BYTES,
        )

    private fun createApnsJwt(teamId: String, keyId: String, privateKeyPem: String): String {
        val header = buildJsonObject {
            put("alg", "ES256")
            put("kid", keyId)
        }
        val now = Instant.now().epochSecond
        val claims = buildJsonObject {
            put("iss", teamId)
            put("iat", now)
            put("exp", now + 3600)
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val headerBase64 = encoder.encodeToString(header.toString().toByteArray())
        val claimsBase64 = encoder.encodeToString(claims.toString().toByteArray())
        val unsignedJwt = "$headerBase64.$claimsBase64"
        val signature = signJwt(unsignedJwt, privateKeyPem)
        return "$unsignedJwt.$signature"
    }

    private fun signJwt(unsignedJwt: String, privateKeyPem: String): String {
        val key = getPrivateKey(privateKeyPem)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(key)
        sig.update(unsignedJwt.toByteArray())
        val derSignature = sig.sign()
        val rawSignature = derToRawSignature(derSignature)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawSignature)
    }

    private fun derToRawSignature(der: ByteArray): ByteArray {
        require(der[0] == 0x30.toByte()) { "Expected DER SEQUENCE tag" }
        var offset = 2
        require(der[offset] == 0x02.toByte()) { "Expected DER INTEGER tag for R" }
        offset++
        val lengthR = der[offset++].toInt() and 0xFF
        val r = der.copyOfRange(offset, offset + lengthR)
        offset += lengthR
        require(der[offset] == 0x02.toByte()) { "Expected DER INTEGER tag for S" }
        offset++
        val lengthS = der[offset++].toInt() and 0xFF
        val s = der.copyOfRange(offset, offset + lengthS)
        val raw = ByteArray(64)
        val rStart = if (r.size > 32) r.size - 32 else 0
        val rLen = if (r.size > 32) 32 else r.size
        System.arraycopy(r, rStart, raw, 32 - rLen, rLen)
        val sStart = if (s.size > 32) s.size - 32 else 0
        val sLen = if (s.size > 32) 32 else s.size
        System.arraycopy(s, sStart, raw, 64 - sLen, sLen)
        return raw
    }

    private fun getPrivateKey(pem: String): PrivateKey {
        val content = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
        val decoded = Base64.getDecoder().decode(content)
        val keySpec = PKCS8EncodedKeySpec(decoded)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(keySpec)
    }

    private data class ApnsJwtCredentials(
        val teamId: String,
        val keyId: String,
        val privateKey: String,
    )

    private companion object {
        const val APPLICATION_DEFAULT_CREDENTIALS = "application-default-credentials"
        const val FIREBASE_APP_PREFIX = "bosca-push"
        const val FINGERPRINT_BYTES = 12
    }
}

private const val ANDROID_CHANNEL_DATA_KEY = "bosca_android_channel_id"
private const val ANDROID_SOUND_DATA_KEY = "bosca_android_sound"
private const val ANDROID_TAG_DATA_KEY = "bosca_android_notification_tag"

internal fun requireValidRelevanceScore(score: Double) {
    require(score in 0.0..1.0) { "APNs relevance score must be between 0.0 and 1.0" }
}

internal fun requireProviderPayloadWithinLimit(provider: String, payload: String) {
    val bytes = payload.toByteArray(Charsets.UTF_8).size
    require(bytes <= 4096) {
        "$provider push payload is $bytes bytes; provider limit is 4096 bytes"
    }
}
