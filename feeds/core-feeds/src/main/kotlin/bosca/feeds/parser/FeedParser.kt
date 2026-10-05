package bosca.feeds.parser

import bosca.feeds.model.RawFeedItem

/** Parses one feed format's envelope body into normalized [RawFeedItem]s. */
interface FeedParser {

    /** Parse [body] into the feed's items; malformed individual entries are skipped, not fatal. */
    fun parse(body: String): List<RawFeedItem>
}
