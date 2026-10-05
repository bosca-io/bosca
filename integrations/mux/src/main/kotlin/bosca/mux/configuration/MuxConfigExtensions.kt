package bosca.mux.configuration

import bosca.mux.client.MuxClientConfig

/**
 * Converts the integration-level [MuxConfiguration] to the
 * transport-level [MuxClientConfig] expected by the Mux REST client.
 */
fun MuxConfiguration.toClientConfig() = MuxClientConfig(
    tokenId = tokenId,
    tokenSecret = tokenSecret,
    playbackPolicy = playbackPolicy,
    defaultSubtitleLanguage = defaultSubtitleLanguage,
    mp4Support = mp4Support,
    videoQuality = videoQuality,
    encodingTier = encodingTier,
    maxResolutionTier = maxResolutionTier,
    test = test,
)
