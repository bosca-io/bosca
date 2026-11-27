package bosca.workops.ui

import kotlin.test.*

class WorkOpsPromotionFamiliesTest {
    private fun environment(id: String, order: Int, sources: List<String> = emptyList(), target: String? = null) =
        WorkOpsEnvironmentRecord(id, id, id, "", order, false, false, "type", sources, 1, targetType = target)

    @Test fun `equal order stops use authored position and mixed target families retain their labels`() {
        val families = workOpsPromotionFamilies(listOf(environment("second-family", 0),
            environment("join", 3, listOf("b", "a"), "APP_STORE"), environment("b", 2, listOf("root"), "PLAY_TRACK"),
            environment("a", 2, listOf("root"), "GENERIC"), environment("root", 0)))
        assertEquals(listOf("second-family"), families.first().environments.map { it.id })
        assertEquals(listOf("root", "b", "a", "join"), families.last().environments.map { it.id })
        assertEquals("Environments", families.last().label)
        val payload = """{"id":"family","environments":[{"id":"self","key":"self","name":"Self","description":"",
          "order":0,"requiresApproval":false,"autoPromote":false,"typeId":"type","sources":["self"],"version":1}]}"""
        val family = assertWorkOpsSerialization(WorkOpsPromotionFamily.serializer(), payload, payload.dropLast(1) + ",\"cyclic\":true}")
        assertTrue(family.cyclic)
        assertTrue(workOpsPromotionFamilies(listOf(environment("self", 0, listOf("self")))).single().cyclic)
    }

    @Test fun `promotion edges override display order and many source merges follow every source`() {
        val rows = listOf(environment("production", 0, listOf("staging", "canary")), environment("canary", 1, listOf("development")), environment("staging", 2, listOf("development")), environment("development", 3))
        val family = workOpsPromotionFamilies(rows).single()
        assertEquals(listOf("development", "canary", "staging", "production"), family.environments.map { it.id }); assertFalse(family.cyclic)
    }

    @Test fun `disjoint infrastructure Play and App Store families retain independent ordered stops`() {
        val rows = listOf(environment("play-production", 5, listOf("play-internal"), "PLAY_TRACK"), environment("play-internal", 4, target = "PLAY_TRACK"),
            environment("staging", 1, listOf("development"), "GENERIC"), environment("development", 0, target = "GENERIC"),
            environment("app-store", 7, listOf("testflight"), "APP_STORE"), environment("testflight", 6, target = "TESTFLIGHT"))
        val families = workOpsPromotionFamilies(rows)
        assertEquals(listOf("Infrastructure", "Google Play", "App Store"), families.map { it.label })
        assertEquals(listOf(listOf("development", "staging"), listOf("play-internal", "play-production"), listOf("testflight", "app-store")), families.map { it.environments.map { row -> row.id } })
        assertEquals(families.map { it.id }, workOpsPromotionFamilies(rows.reversed()).map { it.id })
    }

    @Test fun `cycles duplicate edges unknown sources and isolated environments never drop a stop`() {
        val rows = listOf(environment("a", 2, listOf("b")), environment("b", 1, listOf("a")), environment("entry", 0, listOf("outside")),
            environment("child", 3, listOf("entry", "entry")))
        val families = workOpsPromotionFamilies(rows)
        assertEquals(listOf("entry", "child"), families.first().environments.map { it.id }); assertFalse(families.first().cyclic)
        assertTrue(families.last().cyclic); assertEquals(listOf("b", "a"), families.last().environments.map { it.id })
        assertEquals(rows.map { it.id }.sorted(), families.flatMap { it.environments }.map { it.id }.sorted())
        assertTrue(workOpsPromotionFamilies(emptyList()).isEmpty())
    }
}
