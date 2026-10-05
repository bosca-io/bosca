package bosca.documents.html

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentHtmlConverterTest {

    private fun doc(vararg blocks: JsonElement) = buildJsonObject {
        put("type", JsonPrimitive("doc"))
        put("content", buildJsonArray { blocks.forEach { add(it) } })
    }

    private fun paragraph(text: String) = buildJsonObject {
        put("type", JsonPrimitive("paragraph"))
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(text)) })
        })
    }

    private fun heading(level: Int, text: String) = buildJsonObject {
        put("type", JsonPrimitive("heading"))
        put("attrs", buildJsonObject { put("level", JsonPrimitive(level)) })
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(text)) })
        })
    }

    @Test
    fun `toHtml renders paragraph as p element`() {
        val html = DocumentHtmlConverter.toHtml(doc(paragraph("Hello world")))
        assertTrue(html.contains("<p>Hello world</p>"), "paragraph should render as <p>")
    }

    @Test
    fun `toHtml renders heading with correct level`() {
        val html = DocumentHtmlConverter.toHtml(doc(heading(2, "Title")))
        assertTrue(html.contains("<h2>Title</h2>"), "h2 heading")
    }

    @Test
    fun `toHtml escapes HTML-sensitive characters in text nodes`() {
        val html = DocumentHtmlConverter.toHtml(doc(paragraph("a < b & c > d")))
        assertTrue(html.contains("&lt;"), "< escaped")
        assertTrue(html.contains("&amp;"), "& escaped")
        assertTrue(html.contains("&gt;"), "> escaped")
    }

    @Test
    fun `toHtml renders unknown node types as opaque div blocks`() {
        val customNode = buildJsonObject {
            put("type", JsonPrimitive("custom_embed"))
            put("src", JsonPrimitive("https://example.com"))
        }
        val html = DocumentHtmlConverter.toHtml(doc(customNode))
        assertTrue(html.contains("data-bosca-opaque"), "unknown nodes become opaque blocks")
    }

    @Test
    fun `fromHtml parses p elements back to paragraph nodes`() {
        val result = DocumentHtmlConverter.fromHtml("<p>Hello</p>")
        val content = result.jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("paragraph", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fromHtml parses heading elements with correct level attribute`() {
        val result = DocumentHtmlConverter.fromHtml("<h3>Title</h3>")
        val node = result.jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("heading", node["type"]!!.jsonPrimitive.content)
        assertEquals(3, node["attrs"]!!.jsonObject["level"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `fromHtml parses br as hardBreak`() {
        val result = DocumentHtmlConverter.fromHtml("<p>line1<br/>line2</p>")
        val content = result.jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertTrue(content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "hardBreak" })
    }

    @Test
    fun `toHtml renders bullet list with nested li elements`() {
        val list = buildJsonObject {
            put("type", JsonPrimitive("bulletList"))
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("listItem"))
                    put("content", buildJsonArray { add(paragraph("a")) })
                })
                add(buildJsonObject {
                    put("type", JsonPrimitive("listItem"))
                    put("content", buildJsonArray { add(paragraph("b")) })
                })
            })
        }
        val html = DocumentHtmlConverter.toHtml(doc(list))
        assertTrue(html.contains("<ul>"), "starts with <ul>")
        assertTrue(html.contains("<li><p>a</p></li>"), "has first li")
        assertTrue(html.contains("<li><p>b</p></li>"), "has second li")
    }

    @Test
    fun `fromHtml ul produces bulletList type using camelCase`() {
        val result = DocumentHtmlConverter.fromHtml("<ul><li>one</li><li>two</li></ul>")
        val list = result.jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("bulletList", list["type"]!!.jsonPrimitive.content)
        val items = list["content"]!!.jsonArray
        assertEquals(2, items.size)
        items.forEach {
            assertEquals("listItem", it.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `fromHtml strips whitespace text between block siblings`() {
        // Whitespace between <li> tags must not become spurious text children of the <ul>.
        val result = DocumentHtmlConverter.fromHtml("<ul>\n  <li>one</li>\n  <li>two</li>\n</ul>")
        val list = result.jsonObject["content"]!!.jsonArray[0].jsonObject
        val items = list["content"]!!.jsonArray
        assertEquals(2, items.size, "only the two li children should survive — whitespace must be stripped")
    }

    @Test
    fun `fromHtml wraps inline list-item content in a paragraph`() {
        val result = DocumentHtmlConverter.fromHtml("<ul><li>plain</li></ul>")
        val item = result.jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        val itemContent = item["content"]!!.jsonArray
        assertEquals(1, itemContent.size)
        assertEquals("paragraph", itemContent[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fromHtml treats input without slash as void element`() {
        // CommonMark renders task lists as `<input type="checkbox" disabled="">` with no
        // self-closing slash. The tokenizer reports an OpenTag; we must not treat the
        // sibling text as the input's children.
        val result = DocumentHtmlConverter.fromHtml(
            """<ul><li><input type="checkbox" disabled=""> task one</li></ul>"""
        )
        val item = result.jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        // li with an <input> child should be reclassified as a taskItem.
        assertEquals("taskItem", item["type"]!!.jsonPrimitive.content)
        // and the parent ul becomes a taskList.
        val list = result.jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("taskList", list["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fromHtml treats checked input as taskItem with attrs checked true`() {
        val result = DocumentHtmlConverter.fromHtml(
            """<ul><li><input type="checkbox" disabled="" checked=""> done</li></ul>"""
        )
        val item = result.jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(true, item["attrs"]!!.jsonObject["checked"]!!.jsonPrimitive.content.toBooleanStrict())
    }

    @Test
    fun `toHtml renders table with thead and tbody`() {
        val table = buildJsonObject {
            put("type", JsonPrimitive("table"))
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("tableRow"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("tableHeader"))
                            put("content", buildJsonArray { add(paragraph("h1")) })
                        })
                    })
                })
                add(buildJsonObject {
                    put("type", JsonPrimitive("tableRow"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("tableCell"))
                            put("content", buildJsonArray { add(paragraph("c1")) })
                        })
                    })
                })
            })
        }
        val html = DocumentHtmlConverter.toHtml(doc(table))
        assertTrue(html.contains("<table>"), "table tag present")
        assertTrue(html.contains("<thead>"), "thead wrapping the header row")
        assertTrue(html.contains("<th>h1</th>"), "th cell")
        assertTrue(html.contains("<tbody>"), "tbody wrapping body rows")
        assertTrue(html.contains("<td>c1</td>"), "td cell")
    }

    @Test
    fun `fromHtml table maps thead and tbody transparently`() {
        val result = DocumentHtmlConverter.fromHtml(
            "<table><thead><tr><th>h1</th></tr></thead><tbody><tr><td>c1</td></tr></tbody></table>"
        )
        val table = result.jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("table", table["type"]!!.jsonPrimitive.content)
        val rows = table["content"]!!.jsonArray
        assertEquals(2, rows.size, "thead and tbody should be transparent — only rows survive")
        assertEquals("tableRow", rows[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tableRow", rows[1].jsonObject["type"]!!.jsonPrimitive.content)
        val headerCell = rows[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tableHeader", headerCell["type"]!!.jsonPrimitive.content)
        val bodyCell = rows[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tableCell", bodyCell["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fromHtml wraps table cell inline content in a paragraph`() {
        val result = DocumentHtmlConverter.fromHtml(
            "<table><tr><td>text</td></tr></table>"
        )
        val table = result.jsonObject["content"]!!.jsonArray[0].jsonObject
        val cell = table["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        val cellContent = cell["content"]!!.jsonArray
        assertEquals(1, cellContent.size)
        assertEquals("paragraph", cellContent[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fromHtml emits camelCase node type names`() {
        // Comprehensive guard against the snake_case regression that broke polymorphic
        // deserialization for every list/table/code-block/etc. parsed from markdown.
        val cases = mapOf(
            "<ul><li>x</li></ul>" to "bulletList",
            "<ol><li>x</li></ol>" to "orderedList",
            "<pre><code>x</code></pre>" to "codeBlock",
            "<hr/>" to "horizontalRule",
        )
        for ((html, expectedType) in cases) {
            val node = DocumentHtmlConverter.fromHtml(html).jsonObject["content"]!!.jsonArray[0].jsonObject
            assertEquals(
                expectedType,
                node["type"]!!.jsonPrimitive.content,
                "html `$html` should produce node type $expectedType",
            )
        }
    }

    @Test
    fun `fromHtml drops javascript URL from anchor href`() {
        // The downstream renderer (e.g. TipTap's Link extension) sanitizes on render, but
        // the JSON model is consumed by other paths too — strip the URL at parse time so
        // a hostile markdown input cannot smuggle a script-bearing href through.
        val result = DocumentHtmlConverter.fromHtml("""<p><a href="javascript:alert(1)">click</a></p>""")
        val text = result.jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        val link = (text["marks"] as JsonArray).first { (it as JsonObject)["type"]?.jsonPrimitive?.content == "link" } as JsonObject
        val attrs = link["attrs"] as? JsonObject
        assertEquals(null, attrs?.get("href"), "javascript: href should be dropped — got $attrs")
    }

    @Test
    fun `fromHtml drops data URL from img src`() {
        // Use a payload without `>` since the minimal HtmlTokenizer ends tags at the first
        // `>` regardless of quoting — exercising the URL-scheme allowlist itself, not the
        // tokenizer's unrelated naivety.
        val result = DocumentHtmlConverter.fromHtml("""<p><img src="data:text/plain,hello" alt="x"/></p>""")
        val image = result.jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        val attrs = image["attrs"] as? JsonObject
        assertEquals("x", attrs?.get("alt")?.jsonPrimitive?.content, "alt preserved")
        assertEquals(null, attrs?.get("src"), "data: src should be dropped — got $attrs")
    }

    @Test
    fun `fromHtml preserves http and https URLs`() {
        val cases = listOf(
            "https://example.com/x",
            "http://example.com/x",
            "mailto:a@example.com",
            "tel:+15555555555",
            "/relative/path",
            "#fragment",
            "../parent/path",
        )
        for (url in cases) {
            val result = DocumentHtmlConverter.fromHtml("""<p><a href="$url">x</a></p>""")
            val text = result.jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
            val link = (text["marks"] as JsonArray).first { (it as JsonObject)["type"]?.jsonPrimitive?.content == "link" } as JsonObject
            val attrs = link["attrs"] as JsonObject
            assertEquals(url, attrs["href"]?.jsonPrimitive?.content, "should preserve $url")
        }
    }

    @Test
    fun `fromHtml drops vbscript and file URLs`() {
        val cases = listOf(
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "VBScript:alert(1)",
            "  javascript:alert(1)  ",
        )
        for (url in cases) {
            val result = DocumentHtmlConverter.fromHtml("""<p><a href="$url">x</a></p>""")
            val text = result.jsonObject["content"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
            val link = (text["marks"] as JsonArray).first { (it as JsonObject)["type"]?.jsonPrimitive?.content == "link" } as JsonObject
            val attrs = link["attrs"] as? JsonObject
            assertEquals(null, attrs?.get("href"), "should drop $url")
        }
    }

    @Test
    fun `round-trip preserves paragraph text`() {
        val original = doc(paragraph("Hola mundo"), paragraph("Segunda línea"))
        val html = DocumentHtmlConverter.toHtml(original)
        val roundTripped = DocumentHtmlConverter.fromHtml(html)
        val texts = roundTripped.jsonObject["content"]!!.jsonArray
            .flatMap { it.jsonObject["content"]?.jsonArray ?: emptyList() }
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            .map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue("Hola mundo" in texts, "first paragraph preserved")
        assertTrue("Segunda línea" in texts, "second paragraph preserved")
    }

    @Test
    fun `round-trip preserves opaque blocks for unknown node types`() {
        val customNode = buildJsonObject {
            put("type", JsonPrimitive("video"))
            put("src", JsonPrimitive("video.mp4"))
        }
        val original = doc(paragraph("Before"), customNode, paragraph("After"))
        val html = DocumentHtmlConverter.toHtml(original)
        val roundTripped = DocumentHtmlConverter.fromHtml(html)
        val types = roundTripped.jsonObject["content"]!!.jsonArray
            .map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertTrue(types.contains("paragraph"), "paragraphs survive round-trip")
        // The opaque block should round-trip back to a video node
        val videoNode = roundTripped.jsonObject["content"]!!.jsonArray
            .firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "video" }
        assertTrue(videoNode != null, "opaque video node should round-trip")
    }
}

class HtmlTokenizerTest {

    @Test
    fun `tokenizes open, close, self-closing, and text`() {
        val tokens = HtmlTokenizer.tokenize("<p>Hello<br/>World</p>")
        assertEquals(5, tokens.size)
        assertTrue(tokens[0] is HtmlToken.OpenTag && (tokens[0] as HtmlToken.OpenTag).name == "p")
        assertTrue(tokens[1] is HtmlToken.Text && (tokens[1] as HtmlToken.Text).text == "Hello")
        assertTrue(tokens[2] is HtmlToken.SelfClosingTag && (tokens[2] as HtmlToken.SelfClosingTag).name == "br")
        assertTrue(tokens[3] is HtmlToken.Text && (tokens[3] as HtmlToken.Text).text == "World")
        assertTrue(tokens[4] is HtmlToken.CloseTag && (tokens[4] as HtmlToken.CloseTag).name == "p")
    }

    @Test
    fun `parses attributes with double-quoted, single-quoted, and unquoted values`() {
        val tokens = HtmlTokenizer.tokenize("""<div class="main" id='side' data-x=42>text</div>""")
        val open = tokens[0] as HtmlToken.OpenTag
        assertEquals("main", open.attributes["class"])
        assertEquals("side", open.attributes["id"])
        assertEquals("42", open.attributes["data-x"])
    }

    @Test
    fun `handles unclosed angle bracket gracefully by treating remainder as text`() {
        val tokens = HtmlTokenizer.tokenize("Hello <broken text")
        assertEquals(2, tokens.size)
        assertTrue(tokens[0] is HtmlToken.Text)
        assertTrue(tokens[1] is HtmlToken.Text)
    }

    @Test
    fun `empty input produces empty token list`() {
        assertTrue(HtmlTokenizer.tokenize("").isEmpty())
    }
}
