package bosca.segmentation.service

import bosca.segmentation.model.BannerWeight
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightedRandomSelectTest {

    @Test
    fun `single banner is always selected`() {
        val banner = BannerWeight(id = UUID.random(), weight = 10)
        repeat(100) {
            val result = CampaignServiceImpl.weightedRandomSelect(listOf(banner))
            assertEquals(banner.id, result.id)
        }
    }

    @Test
    fun `banner with weight 0 among others is never selected when others have positive weight`() {
        val zero = BannerWeight(id = UUID.random(), weight = 0)
        val nonZero = BannerWeight(id = UUID.random(), weight = 100)
        val results = (1..1000).map { CampaignServiceImpl.weightedRandomSelect(listOf(zero, nonZero)) }
        assertTrue(results.all { it.id == nonZero.id })
    }

    @Test
    fun `all zero weights falls back to random selection`() {
        val a = BannerWeight(id = UUID.random(), weight = 0)
        val b = BannerWeight(id = UUID.random(), weight = 0)
        val results = (1..1000).map { CampaignServiceImpl.weightedRandomSelect(listOf(a, b)) }
        val aCount = results.count { it.id == a.id }
        assertTrue(aCount > 0, "Expected banner A to be selected at least once")
        assertTrue(aCount < 1000, "Expected banner B to be selected at least once")
    }

    @Test
    fun `weighted distribution is approximately correct`() {
        val heavy = BannerWeight(id = UUID.random(), weight = 80)
        val light = BannerWeight(id = UUID.random(), weight = 20)
        val iterations = 10_000
        val results = (1..iterations).map { CampaignServiceImpl.weightedRandomSelect(listOf(heavy, light)) }
        val heavyCount = results.count { it.id == heavy.id }
        val heavyRatio = heavyCount.toDouble() / iterations
        // Should be ~0.80, allow +-0.05
        assertTrue(heavyRatio in 0.70..0.90, "Expected ~80% heavy selection, got ${heavyRatio * 100}%")
    }

    @Test
    fun `three banners with equal weights are roughly evenly distributed`() {
        val a = BannerWeight(id = UUID.random(), weight = 10)
        val b = BannerWeight(id = UUID.random(), weight = 10)
        val c = BannerWeight(id = UUID.random(), weight = 10)
        val iterations = 10_000
        val results = (1..iterations).map { CampaignServiceImpl.weightedRandomSelect(listOf(a, b, c)) }
        val aCount = results.count { it.id == a.id }
        val bCount = results.count { it.id == b.id }
        val cCount = results.count { it.id == c.id }
        val aRatio = aCount.toDouble() / iterations
        val bRatio = bCount.toDouble() / iterations
        val cRatio = cCount.toDouble() / iterations
        // Each should be ~0.33, allow +-0.07
        assertTrue(aRatio in 0.26..0.40, "Expected ~33% for A, got ${aRatio * 100}%")
        assertTrue(bRatio in 0.26..0.40, "Expected ~33% for B, got ${bRatio * 100}%")
        assertTrue(cRatio in 0.26..0.40, "Expected ~33% for C, got ${cRatio * 100}%")
    }

    @Test
    fun `weight of 1 vs weight of 99 yields approximately 1 percent vs 99 percent`() {
        val rare = BannerWeight(id = UUID.random(), weight = 1)
        val common = BannerWeight(id = UUID.random(), weight = 99)
        val iterations = 10_000
        val results = (1..iterations).map { CampaignServiceImpl.weightedRandomSelect(listOf(rare, common)) }
        val rareCount = results.count { it.id == rare.id }
        val rareRatio = rareCount.toDouble() / iterations
        assertTrue(rareRatio < 0.05, "Expected <5% rare selection, got ${rareRatio * 100}%")
    }
}
