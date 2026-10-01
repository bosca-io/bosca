package bosca.feeds.parser

import bosca.feeds.model.RawFeedItem
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser

/**
 * RSS 2.0 parser. Items are `<item>` under `<channel>`; the unique id is `<guid>` (falling back to
 * `<link>`). Reads the `content:encoded` and `dc:creator` namespaced extensions when present.
 * Parsed in XML mode so tag case and namespaces are preserved.
 */
class RssFeedParser : FeedParser {

    override fun parse(body: String): List<RawFeedItem> {
        val doc = Ksoup.parse(html = body, parser = Parser.xmlParser())
        return doc.select("item").mapNotNull { item ->
            val guid = item.selectFirst("guid")?.text()?.ifBlank { null }
                ?: item.selectFirst("link")?.text()?.ifBlank { null }
                ?: return@mapNotNull null
            RawFeedItem(
                guid = guid,
                title = item.selectFirst("title")?.text().orEmpty(),
                link = item.selectFirst("link")?.text()?.ifBlank { null },
                author = item.selectFirst("dc|creator")?.text()?.ifBlank { null }
                    ?: item.selectFirst("author")?.text()?.ifBlank { null },
                publishedAt = item.selectFirst("pubDate")?.text()?.ifBlank { null },
                summary = item.selectFirst("description")?.text()?.ifBlank { null },
                content = item.selectFirst("content|encoded")?.text()?.ifBlank { null },
                imageUrl = MediaImages.fromXmlItem(item)
                    ?: item.select("enclosure").firstOrNull { it.attr("type").startsWith("image/") }
                        ?.attr("url")?.ifBlank { null },
                imageCredit = MediaImages.creditFromXmlItem(item),
            )
        }
    }
}
