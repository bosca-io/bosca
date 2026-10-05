package bosca.cdn

class NoOpCdnManager : CdnManager {

    override suspend fun clearCache(): Boolean {
        error("CDN is not configured")
    }
}
