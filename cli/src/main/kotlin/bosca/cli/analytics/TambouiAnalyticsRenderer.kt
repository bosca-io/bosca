package bosca.cli.analytics

import dev.tamboui.buffer.Buffer
import dev.tamboui.layout.Constraint
import dev.tamboui.layout.Direction
import dev.tamboui.layout.Rect
import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.widget.Widget
import dev.tamboui.widgets.barchart.Bar
import dev.tamboui.widgets.barchart.BarChart
import dev.tamboui.widgets.barchart.BarGroup
import dev.tamboui.widgets.block.Block
import dev.tamboui.widgets.block.Borders
import dev.tamboui.widgets.chart.Axis
import dev.tamboui.widgets.chart.Chart
import dev.tamboui.widgets.chart.Dataset
import dev.tamboui.widgets.chart.GraphType
import dev.tamboui.widgets.paragraph.Paragraph
import dev.tamboui.widgets.sparkline.Sparkline
import dev.tamboui.widgets.table.Row
import dev.tamboui.widgets.table.Table
import dev.tamboui.widgets.table.TableState
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

private const val DEFAULT_RENDER_WIDTH = 80
private const val DASHBOARD_GRID_COLUMNS = 48
private const val MAX_DASHBOARD_GRID_ROWS = 200
private const val MAX_RENDERED_RECORDS = 20

internal data class AnalyticsGridPlacement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal data class RenderedAnalyticsVisualization(
    val name: String,
    val description: String,
    val type: String,
    val configuration: JsonObject?,
    val records: List<JsonElement>,
    val placement: AnalyticsGridPlacement? = null,
)

internal object TambouiAnalyticsRenderer {

    suspend fun render(
        title: String,
        visualizations: List<RenderedAnalyticsVisualization>,
        output: (String) -> Unit,
    ) {
        output(buildDashboardLines(title, visualizations).joinToString("\n"))
    }
}

internal fun buildDashboardLines(
    title: String,
    visualizations: List<RenderedAnalyticsVisualization>,
    width: Int = DEFAULT_RENDER_WIDTH,
): List<String> = buildList {
    val renderWidth = width.coerceAtLeast(24)
    val renderableVisualizations = visualizations.filterNot { it.type == "DATEPICKER" }
    add(title)
    add("═".repeat(title.length.coerceAtLeast(12).coerceAtMost(renderWidth)))
    if (renderableVisualizations.isEmpty()) {
        add("No visualizations.")
        return@buildList
    }
    val positioned = renderableVisualizations.filter { it.placement != null }
    if (positioned.isNotEmpty()) {
        addAll(buildGridLines(positioned, renderWidth))
    }
    val unpositioned = renderableVisualizations.filter { it.placement == null }
    unpositioned.forEachIndexed { index, visualization ->
        if (positioned.isNotEmpty() || index > 0) add("")
        addAll(buildVisualizationLines(visualization, renderWidth))
    }
}

private fun buildGridLines(
    visualizations: List<RenderedAnalyticsVisualization>,
    width: Int,
): List<String> {
    val placements = visualizations.mapNotNull { it.placement }
    val columns = maxOf(
        DASHBOARD_GRID_COLUMNS,
        placements.maxOfOrNull { it.x.coerceAtLeast(0) + it.width.coerceAtLeast(1) } ?: 0,
    )
    val rows = (placements.maxOfOrNull {
        it.y.coerceAtLeast(0) + it.height.coerceAtLeast(1)
    } ?: 1).coerceAtMost(MAX_DASHBOARD_GRID_ROWS)
    val canvas = Array(rows) { CharArray(width) { ' ' } }

    visualizations.sortedWith(
        compareBy<RenderedAnalyticsVisualization> { it.placement?.y }
            .thenBy { it.placement?.x },
    ).forEach { visualization ->
        val placement = visualization.placement ?: return@forEach
        val gridLeft = placement.x.coerceIn(0, columns - 1)
        val gridRight = (gridLeft + placement.width.coerceAtLeast(1)).coerceAtMost(columns)
        val left = gridLeft * width / columns
        val right = (gridRight * width / columns).coerceIn(left + 1, width)
        val top = placement.y.coerceIn(0, rows - 1)
        val height = placement.height.coerceAtLeast(1).coerceAtMost(rows - top)
        val cellWidth = right - left
        val lines = fitGridCell(
            buildVisualizationLines(visualization, cellWidth),
            cellWidth,
            height,
        )
        lines.forEachIndexed { rowOffset, line ->
            line.forEachIndexed { columnOffset, character ->
                if (columnOffset < cellWidth) {
                    canvas[top + rowOffset][left + columnOffset] = character
                }
            }
        }
    }
    return canvas.map { String(it).trimEnd() }
        .dropLastWhile(String::isBlank)
}

private fun fitGridCell(
    source: List<String>,
    width: Int,
    height: Int,
): List<String> {
    if (height <= 0) return emptyList()
    val blank = " ".repeat(width)
    val normalized = source.map { it.take(width).padEnd(width) }
    val hasBorder = width >= 2 &&
        normalized.firstOrNull()?.trimStart()?.startsWith("┌") == true &&
        normalized.lastOrNull()?.trimStart()?.startsWith("└") == true
    val lines = if (hasBorder) {
        normalized.mapIndexed { index, line ->
            when (index) {
                0 -> line.withGridCellEdges('┌', '┐')
                normalized.lastIndex -> line.withGridCellEdges('└', '┘')
                else -> line.withGridCellEdges('│', '│')
            }
        }
    } else {
        normalized
    }
    if (lines.isEmpty()) return List(height) { blank }
    if (height == 1) return listOf(lines.first())

    if (!hasBorder) return lines.take(height) + List((height - lines.size).coerceAtLeast(0)) { blank }

    val emptyBorderRow = "│${" ".repeat((width - 2).coerceAtLeast(0))}│".take(width).padEnd(width)
    if (lines.size <= height) {
        return buildList {
            add(lines.first())
            addAll(lines.drop(1).dropLast(1))
            repeat(height - lines.size) { add(emptyBorderRow) }
            add(lines.last())
        }
    }
    if (height == 2) return listOf(lines.first(), lines.last())

    val retainedRows = (height - 3).coerceAtLeast(0)
    val omittedRows = (lines.size - 2 - retainedRows).coerceAtLeast(1)
    val markerText = "… $omittedRows rows omitted"
    val marker = if (width >= 2) {
        "│${markerText.take(width - 2).padEnd(width - 2)}│"
    } else {
        markerText.take(width).padEnd(width)
    }
    return listOf(lines.first()) +
        lines.drop(1).dropLast(1).take(retainedRows) +
        marker +
        lines.last()
}

private fun String.withGridCellEdges(left: Char, right: Char): String {
    if (length < 2) return this
    val characters = toCharArray()
    characters[0] = left
    characters[characters.lastIndex] = right
    return String(characters)
}

internal fun buildVisualizationLines(
    visualization: RenderedAnalyticsVisualization,
    width: Int = DEFAULT_RENDER_WIDTH,
): List<String> = when (visualization.type) {
    "NUMBER" -> renderNumber(visualization, width)
    "BAR" -> renderBarChart(visualization, width)
    "LINE", "STACKED_AREA" -> renderSparklines(visualization, width)
    "PIE", "DOUGHNUT" -> renderPieTable(visualization, width)
    "SCATTER", "BUBBLE" -> renderScatterChart(visualization, width)
    "LABEL" -> renderParagraph(
        visualization,
        visualization.configuration.string("label") ?: visualization.name,
        width,
    )
    "DATEPICKER" -> renderParagraph(
        visualization,
        "Date parameters: ${
            visualization.configuration.string("startDateParam") ?: "startDate"
        } → ${visualization.configuration.string("endDateParam") ?: "endDate"}",
        width,
    )
    "TABLE" -> renderTable(visualization, width)
    else -> renderTable(
        visualization,
        width,
        titleSuffix = "${visualization.type} · tabular preview",
    )
}

private fun renderNumber(
    visualization: RenderedAnalyticsVisualization,
    width: Int,
): List<String> {
    val row = visualization.records.firstOrNull() as? JsonObject
    val key = visualization.configuration.string("value") ?: row?.keys?.firstOrNull()
    val value = key?.let { row?.get(it) }
    return renderParagraph(
        visualization,
        configuredDisplayValue(visualization.configuration, key, value),
        width,
        centered = true,
    )
}

private fun renderParagraph(
    visualization: RenderedAnalyticsVisualization,
    text: String,
    width: Int,
    centered: Boolean = false,
): List<String> {
    val builder = Paragraph.builder()
        .text(text)
        .block(visualizationBlock(visualization))
    if (centered) builder.centered()
    return renderWidget(builder.build(), width, 5)
}

private fun renderBarChart(
    visualization: RenderedAnalyticsVisualization,
    width: Int,
): List<String> {
    val rows = visualization.records.mapNotNull { it as? JsonObject }
    if (rows.isEmpty()) return renderParagraph(visualization, "No records.", width)
    val (xKey, yKeys) = axes(visualization.configuration, rows)
    if (xKey == null || yKeys.isEmpty()) return renderTable(visualization, width)

    val entries = rows.take(MAX_RENDERED_RECORDS).flatMap { row ->
        yKeys.mapNotNull { yKey ->
            row.number(yKey)?.let { value ->
                val label = buildString {
                    append(
                        configuredDisplayValue(
                            visualization.configuration,
                            xKey,
                            row[xKey],
                        ).take(20),
                    )
                    if (yKeys.size > 1) append(" / ${yKey.take(12)}")
                }
                label to value
            }
        }
    }
    if (entries.isEmpty() || entries.any { it.second < 0.0 }) {
        return renderTable(visualization, width)
    }
    val maxValue = entries.maxOf { it.second }.takeIf { it > 0.0 } ?: 1.0
    val groups = entries.map { (label, value) ->
        val scaled = if (value <= 0.0) 0L else {
            ((value / maxValue) * 1_000).roundToLong().coerceAtLeast(1)
        }
        BarGroup.of(
            Bar.builder()
                .value(scaled)
                .label(label)
                .textValue(formatNumber(value))
                .build(),
        )
    }
    val chart = BarChart.builder()
        .data(groups)
        .max(1_150)
        .direction(Direction.HORIZONTAL)
        .barWidth(1)
        .barGap(0)
        .groupGap(0)
        .barColor(Color.CYAN)
        .block(visualizationBlock(visualization))
        .build()
    return renderWidget(chart, width, groups.size + 2)
}

private fun renderSparklines(
    visualization: RenderedAnalyticsVisualization,
    width: Int,
): List<String> {
    val rows = visualization.records.mapNotNull { it as? JsonObject }
    if (rows.isEmpty()) return renderParagraph(visualization, "No records.", width)
    val (xKey, yKeys) = axes(visualization.configuration, rows)
    if (yKeys.isEmpty()) return renderTable(visualization, width)

    return buildList {
        yKeys.forEachIndexed { index, yKey ->
            if (index > 0) add("")
            val values = rows.mapNotNull { it.number(yKey) }
            if (values.isEmpty()) {
                addAll(renderTable(visualization, width))
                return@buildList
            }
            val labels = xKey?.let { key ->
                val first = configuredDisplayValue(
                    visualization.configuration,
                    key,
                    rows.firstOrNull()?.get(key),
                )
                val last = configuredDisplayValue(
                    visualization.configuration,
                    key,
                    rows.lastOrNull()?.get(key),
                )
                "$first → $last"
            }
            val range = "${formatNumber(values.min())}–${formatNumber(values.max())}"
            val block = visualizationBlock(
                visualization = visualization,
                titleSuffix = if (yKeys.size == 1) visualization.type else "${visualization.type} · $yKey",
                detail = listOfNotNull(labels, range).joinToString(" · "),
            )
            val sparkline = Sparkline.builder()
                .data(*normalize(values))
                .autoMax()
                .foreground(Color.CYAN)
                .block(block)
                .build()
            addAll(renderWidget(sparkline, width, 6))
        }
    }
}

private fun renderPieTable(
    visualization: RenderedAnalyticsVisualization,
    width: Int,
): List<String> {
    val rows = visualization.records.mapNotNull { it as? JsonObject }
    if (rows.isEmpty()) return renderParagraph(visualization, "No records.", width)
    val labelKey = visualization.configuration.string("label") ?: rows.first().keys.firstOrNull()
    val valueKey = visualization.configuration.string("value")
        ?: inferNumericKeys(rows).firstOrNull()
    if (labelKey == null || valueKey == null) return renderTable(visualization, width)

    val values = rows.map { (it.number(valueKey) ?: 0.0).coerceAtLeast(0.0) }
    val total = values.sum().takeIf { it > 0.0 } ?: 1.0
    val previewRecords = rows.mapIndexed { index, row ->
        buildJsonObject {
            put(
                "label",
                configuredDisplayValue(visualization.configuration, labelKey, row[labelKey]),
            )
            put("value", displayValue(row[valueKey]))
            put("share", "%.1f%%".format(Locale.ROOT, values[index] / total * 100))
        }
    }
    return renderTable(
        visualization.copy(
            configuration = buildJsonObject {
                put(
                    "columns",
                    JsonArray(listOf("label", "value", "share").map(::JsonPrimitive)),
                )
            },
            records = previewRecords,
        ),
        width,
        titleSuffix = "${visualization.type} · tabular preview",
    )
}

private fun renderScatterChart(
    visualization: RenderedAnalyticsVisualization,
    width: Int,
): List<String> {
    val rows = visualization.records.mapNotNull { it as? JsonObject }
    if (rows.isEmpty()) return renderParagraph(visualization, "No records.", width)
    val numericKeys = inferNumericKeys(rows)
    val xKey = visualization.configuration.string("x") ?: numericKeys.firstOrNull()
    val yKey = visualization.configuration.stringList("y").firstOrNull()
        ?: numericKeys.firstOrNull { it != xKey }
    if (xKey == null || yKey == null) return renderTable(visualization, width)

    val points = rows.take(30).mapNotNull { row ->
        val x = row.number(xKey)
        val y = row.number(yKey)
        if (x != null && y != null && x.isFinite() && y.isFinite()) doubleArrayOf(x, y) else null
    }
    if (points.isEmpty()) return renderTable(visualization, width)

    val dataset = Dataset.builder()
        .name(visualization.name)
        .data(points)
        .marker(Dataset.Marker.DOT)
        .graphType(GraphType.SCATTER)
        .style(Style.EMPTY.cyan())
        .build()
    val chart = Chart.builder()
        .datasets(dataset)
        .xAxis(axis(xKey, points.map { it[0] }, visualization.configuration))
        .yAxis(axis(yKey, points.map { it[1] }, visualization.configuration))
        .hideLegend()
        .block(visualizationBlock(visualization))
        .build()
    return renderWidget(chart, width, 14)
}

private fun axis(
    name: String,
    values: List<Double>,
    configuration: JsonObject?,
): Axis {
    val min = values.min()
    val max = values.max()
    val bounds = if (min == max) (min - 1.0) to (max + 1.0) else min to max
    return Axis.builder()
        .title(name)
        .bounds(bounds.first, bounds.second)
        .labels(
            configuredDisplayValue(configuration, name, JsonPrimitive(bounds.first)),
            configuredDisplayValue(configuration, name, JsonPrimitive(bounds.second)),
        )
        .build()
}

private fun renderTable(
    visualization: RenderedAnalyticsVisualization,
    width: Int,
    titleSuffix: String = visualization.type,
): List<String> {
    if (visualization.records.isEmpty()) {
        return renderParagraph(visualization, "No records.", width)
    }
    val records = visualization.records.take(MAX_RENDERED_RECORDS).map { record ->
        record as? JsonObject ?: buildJsonObject { put("value", record) }
    }
    val discoveredColumns = records.flatMap { it.keys }.distinct()
    val configuredColumns = visualization.configuration.stringList("columns")
    val columns = configuredColumns.filter { it in discoveredColumns }
        .ifEmpty { discoveredColumns }
    if (columns.isEmpty()) return renderParagraph(visualization, "No records.", width)

    val rows = records.map { record ->
        Row.from(
            *columns.map {
                configuredDisplayValue(visualization.configuration, it, record[it])
            }.toTypedArray(),
        )
    }.toMutableList()
    if (visualization.records.size > records.size) {
        rows += Row.from(
            *listOf(
                "… ${visualization.records.size - records.size} more records",
                *Array((columns.size - 1).coerceAtLeast(0)) { "" },
            ).toTypedArray(),
        )
    }
    val table = Table.builder()
        .header(Row.from(*columns.toTypedArray()).style(Style.EMPTY.bold()))
        .rows(rows)
        .widths(columns.map { Constraint.fill() })
        .columnSpacing(2)
        .block(visualizationBlock(visualization, titleSuffix))
        .build()
    val area = Rect(0, 0, width.coerceAtLeast(24), rows.size + 3)
    val buffer = Buffer.empty(area)
    table.render(area, buffer, TableState())
    return bufferLines(buffer)
}

private fun visualizationBlock(
    visualization: RenderedAnalyticsVisualization,
    titleSuffix: String = visualization.type,
    detail: String? = null,
): Block {
    val footer = listOfNotNull(
        visualization.description.takeIf { it.isNotBlank() },
        detail?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    return Block.builder()
        .borders(Borders.ALL)
        .title(
            if (visualization.placement == null) "${visualization.name} [$titleSuffix]"
            else visualization.name,
        )
        .apply {
            if (footer.isNotEmpty()) titleBottom(footer.take(68))
        }
        .build()
}

private fun renderWidget(widget: Widget, width: Int, height: Int): List<String> {
    val area = Rect(0, 0, width.coerceAtLeast(4), height.coerceAtLeast(3))
    val buffer = Buffer.empty(area)
    widget.render(area, buffer)
    return bufferLines(buffer)
}

private fun bufferLines(buffer: Buffer): List<String> =
    (0 until buffer.height())
        .map { y ->
            buildString {
                for (x in 0 until buffer.width()) {
                    val cell = buffer.get(x, y)
                    if (!cell.isContinuation) append(cell.symbol())
                }
            }.trimEnd()
        }
        .dropLastWhile(String::isBlank)
        .ifEmpty { listOf("") }

private fun axes(
    configuration: JsonObject?,
    rows: List<JsonObject>,
): Pair<String?, List<String>> {
    val keys = rows.flatMap { it.keys }.distinct()
    val configuredX = configuration.string("x")
        ?.takeIf { it in keys }
    val x = configuredX
        ?: rows.firstOrNull()?.keys?.firstOrNull { key ->
            rows.any { it[key] !is JsonPrimitive || it.number(key) == null }
        }
        ?: rows.firstOrNull()?.keys?.firstOrNull()
    val numeric = inferNumericKeys(rows).filterNot { it == x }
    val configuredY = configuration.stringList("y")
        .filter { it != x && it in numeric }
    return x to configuredY.ifEmpty { numeric.take(3) }
}

private fun inferNumericKeys(rows: List<JsonObject>): List<String> =
    rows.flatMap { it.keys }.distinct().filter { key -> rows.any { it.number(key) != null } }

private fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.content

private fun JsonObject?.stringList(key: String): List<String> = when (val value = this?.get(key)) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
    is JsonPrimitive -> listOf(value.content)
    else -> emptyList()
}

private fun configuredDisplayValue(
    configuration: JsonObject?,
    key: String?,
    value: JsonElement?,
): String {
    if (key == null) return displayValue(value)
    val fields = configuration?.get("fields") as? JsonObject
    val field = fields?.get(key) as? JsonObject
    if (!field.string("type").equals("date", ignoreCase = true)) return displayValue(value)

    val instant = (value as? JsonPrimitive)?.let(::dateInstant)
        ?: return displayValue(value)
    val pattern = field.string("format") ?: "dd MMM"
    val formatter = runCatching {
        DateTimeFormatter.ofPattern(pattern, Locale.US)
    }.getOrNull() ?: return displayValue(value)
    return formatter.format(instant.atZone(ZoneOffset.UTC))
}

private fun dateInstant(value: JsonPrimitive): Instant? {
    value.doubleOrNull?.takeIf(Double::isFinite)?.let {
        return runCatching { Instant.ofEpochMilli(it.toLong()) }.getOrNull()
    }
    val content = value.content
    return runCatching { Instant.parse(content) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(content).toInstant() }.getOrNull()
        ?: runCatching {
            LocalDate.parse(content).atStartOfDay(ZoneOffset.UTC).toInstant()
        }.getOrNull()
}

private fun normalize(values: List<Double>): LongArray {
    val min = values.min()
    val max = values.max()
    if (min == max) return LongArray(values.size) { 500 }
    return values.map { value ->
        (((value - min) / (max - min)) * 1_000).roundToLong()
    }.toLongArray()
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else "%.2f".format(Locale.ROOT, value)

internal fun sampleAnalyticsVisualizations(): List<RenderedAnalyticsVisualization> {
    val trend = listOf(
        "Mon" to 18,
        "Tue" to 27,
        "Wed" to 23,
        "Thu" to 39,
        "Fri" to 48,
        "Sat" to 31,
        "Sun" to 44,
    ).map { (day, sessions) ->
        buildJsonObject {
            put("day", day)
            put("sessions", sessions)
        }
    }
    val categories = listOf(
        "Organic" to 52,
        "Direct" to 28,
        "Referral" to 20,
    ).map { (source, share) ->
        buildJsonObject {
            put("source", source)
            put("share", share)
        }
    }
    return listOf(
        RenderedAnalyticsVisualization(
            name = "Active sessions",
            description = "Single-value visualization",
            type = "NUMBER",
            configuration = buildJsonObject { put("value", "sessions") },
            records = listOf(buildJsonObject { put("sessions", 230) }),
        ),
        RenderedAnalyticsVisualization(
            name = "Sessions by day",
            description = "Bar visualization",
            type = "BAR",
            configuration = buildJsonObject {
                put("x", "day")
                put("y", JsonArray(listOf(JsonPrimitive("sessions"))))
            },
            records = trend,
        ),
        RenderedAnalyticsVisualization(
            name = "Weekly trend",
            description = "Line visualization",
            type = "LINE",
            configuration = buildJsonObject {
                put("x", "day")
                put("y", JsonArray(listOf(JsonPrimitive("sessions"))))
            },
            records = trend,
        ),
        RenderedAnalyticsVisualization(
            name = "Traffic sources",
            description = "Shares shown as an exact tabular terminal preview",
            type = "PIE",
            configuration = buildJsonObject {
                put("label", "source")
                put("value", "share")
            },
            records = categories,
        ),
        RenderedAnalyticsVisualization(
            name = "Sample records",
            description = "Table visualization",
            type = "TABLE",
            configuration = null,
            records = trend.take(4),
        ),
    )
}
