package bosca.cli.analytics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue

class TambouiAnalyticsRendererTest {

    @Test
    fun `sample dashboard renders with Tamboui widgets`() {
        val output = buildDashboardLines("Analytics sample", sampleAnalyticsVisualizations())
            .joinToString("\n")

        assertTrue("Active sessions [NUMBER]" in output)
        assertTrue("230" in output)
        assertTrue("Sessions by day [BAR]" in output)
        assertTrue("█" in output)
        assertTrue("Weekly trend [LINE]" in output)
        assertTrue("▁▂▃▄▅▆▇█".any { it in output })
        assertTrue("Traffic sources [PIE · tabular preview]" in output)
        assertTrue("52.0%" in output)
        assertTrue("Sample records [TABLE]" in output)
        assertTrue("┌" in output)
    }

    @Test
    fun `configured axes select the requested fields`() {
        val visualization = RenderedAnalyticsVisualization(
            name = "Conversions",
            description = "",
            type = "BAR",
            configuration = buildJsonObject {
                put("x", "channel")
                put("y", JsonArray(listOf(JsonPrimitive("conversions"))))
            },
            records = listOf(
                buildJsonObject {
                    put("channel", "Direct")
                    put("visits", 100)
                    put("conversions", 20)
                },
            ),
        )

        val output = buildVisualizationLines(visualization).joinToString("\n")

        assertTrue("Direct" in output)
        assertTrue("20" in output)
        assertTrue("visits" !in output)
    }

    @Test
    fun `dashboard placement coordinates are not treated as chart axes`() {
        val visualization = RenderedAnalyticsVisualization(
            name = "Daily sessions",
            description = "",
            type = "BAR",
            configuration = buildJsonObject {
                put("x", 0)
                put("y", 0)
                put("w", 6)
                put("h", 3)
            },
            records = listOf(
                buildJsonObject {
                    put("date", 1_753_660_800_000)
                    put("sessions", 12)
                },
                buildJsonObject {
                    put("date", 1_753_747_200_000)
                    put("sessions", 18)
                },
            ),
        )

        val output = buildVisualizationLines(visualization).joinToString("\n")

        assertTrue("Daily sessions [BAR]" in output)
        assertTrue("█" in output)
    }

    @Test
    fun `date field settings format epoch milliseconds in chart labels`() {
        val visualization = RenderedAnalyticsVisualization(
            name = "Daily sessions",
            description = "",
            type = "BAR",
            configuration = buildJsonObject {
                put("x", "date")
                put("y", JsonArray(listOf(JsonPrimitive("sessions"))))
                put("fields", buildJsonObject {
                    put("date", buildJsonObject {
                        put("type", "date")
                        put("format", "MM/dd/yy")
                    })
                })
            },
            records = listOf(
                buildJsonObject {
                    put("date", 1_753_660_800_000)
                    put("sessions", 12)
                },
            ),
        )

        val output = buildVisualizationLines(visualization).joinToString("\n")

        assertTrue("07/28/25" in output)
        assertTrue("1753660800000" !in output)
    }

    @Test
    fun `dashboard grid renders visualizations side by side`() {
        fun number(name: String, value: Int, placement: AnalyticsGridPlacement) =
            RenderedAnalyticsVisualization(
                name = name,
                description = "",
                type = "NUMBER",
                configuration = buildJsonObject { put("value", "value") },
                records = listOf(buildJsonObject { put("value", value) }),
                placement = placement,
            )

        val output = buildDashboardLines(
            title = "Grid",
            visualizations = listOf(
                number("Left", 1, AnalyticsGridPlacement(0, 0, 5, 5)),
                number("Right", 2, AnalyticsGridPlacement(5, 0, 5, 5)),
            ),
            width = 80,
        )

        assertTrue(output.any { "Left" in it && "Right" in it })
        assertTrue(output.any { "1" in it && "2" in it })
        assertTrue(output.any { "┐┌" in it })
    }

    @Test
    fun `dashboard omits date picker controls`() {
        val output = buildDashboardLines(
            title = "Grid",
            visualizations = listOf(
                RenderedAnalyticsVisualization(
                    name = "Date Picker",
                    description = "",
                    type = "DATEPICKER",
                    configuration = null,
                    records = emptyList(),
                    placement = AnalyticsGridPlacement(0, 0, 48, 4),
                ),
            ),
        ).joinToString("\n")

        assertTrue("Date Picker" !in output)
        assertTrue("No visualizations." in output)
    }
}
