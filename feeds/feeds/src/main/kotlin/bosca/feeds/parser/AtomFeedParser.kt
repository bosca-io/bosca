package bosca.feeds.parser

import bosca.feeds.model.RawFeedItem
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser

/**
 * Atom 1.0 parser. Items are `<entry>`; the unique id is `<id>`. The alternate `<link rel="alternate">`
 * (or the first link) is the item URL. Parsed in XML mode.
 */
class AtomFeedParser : FeedParser {

    override fun parse(body: String): List<RawFeedItem> {
        val doc = Ksoup.parse(html = body, parser = Parser.xmlParser())
        return doc.select("entry").mapNotNull { entry ->
            val id = entry.selectFirst("id")?.text()?.ifBlank { null } ?: return@mapNotNull null
            val link = entry.select("link")
                .firstOrNull { it.attr("rel").isBlank() || it.attr("rel") == "alternate" }
                ?.attr("href")?.ifBlank { null }
                ?: entry.selectFirst("link")?.attr("href")?.ifBlank { null }
            RawFeedItem(
                guid = id,
                title = entry.selectFirst("title")?.text().orEmpty(),
                link = link,
                author = entry.selectFirst("author")?.selectFirst("name")?.text()?.ifBlank { null },
                publishedAt = (entry.selectFirst("published") ?: entry.selectFirst("updated"))?.text()?.ifBlank { null },
                summary = entry.selectFirst("summary")?.text()?.ifBlank { null },
                content = entry.selectFirst("content")?.text()?.ifBlank { null },
                imageUrl = MediaImages.fromXmlItem(entry)
                    ?: entry.select("link")
                        .firstOrNull { it.attr("rel") == "enclosure" && it.attr("type").startsWith("image/") }
                        ?.attr("href")?.ifBlank { null },
                imageCredit = MediaImages.creditFromXmlItem(entry),
            )
        }
    }
}
