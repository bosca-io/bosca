package bosca.feeds.service

import bosca.feeds.model.RawFeedItem
import bosca.feeds.model.SourceType
import bosca.feeds.parser.AtomFeedParser
import bosca.feeds.parser.FeedContentParser
import bosca.feeds.parser.JsonFeedParser
import bosca.feeds.parser.RssFeedParser
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json

/** Dispatches feed-body parsing to the per-format parser by [SourceType]. */
@ServiceImplementation
class FeedContentParserImpl(
    private val json: Json,
) : FeedContentParser {

    private val rss = RssFeedParser()
    private val atom = AtomFeedParser()
    private val jsonFeed = JsonFeedParser(json)

    override fun parse(type: SourceType, body: String): List<RawFeedItem> = when (type) {
        SourceType.RSS -> rss.parse(body)
        SourceType.ATOM -> atom.parse(body)
        SourceType.JSON_FEED -> jsonFeed.parse(body)
        SourceType.API -> throw IllegalArgumentException(
            "API sources use a configured adapter, not the envelope parser",
        )
    }
}
