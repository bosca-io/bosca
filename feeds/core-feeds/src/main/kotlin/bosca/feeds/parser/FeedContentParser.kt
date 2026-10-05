package bosca.feeds.parser

import bosca.feeds.model.RawFeedItem
import bosca.feeds.model.SourceType
import bosca.service.Service

/**
 * Parses a fetched feed body into normalized [RawFeedItem]s, dispatching by the source's
 * [SourceType]. `API` sources are handled by a configured adapter, not this envelope parser.
 */
interface FeedContentParser : Service {

    fun parse(type: SourceType, body: String): List<RawFeedItem>
}
