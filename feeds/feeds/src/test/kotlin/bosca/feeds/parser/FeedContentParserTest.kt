package bosca.feeds.parser

import bosca.feeds.model.SourceType
import bosca.feeds.service.FeedContentParserImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**RSS / ATOM / JSON Feed envelopes parse into normalized RawFeedItems; API is unsupported here. */
class FeedContentParserTest {

    private val parser = FeedContentParserImpl(Json { ignoreUnknownKeys = true })

    @Test
    fun `parses RSS items with guid, content-encoded and dc-creator`() {
        val rss = """
            <?xml version="1.0"?>
            <rss version="2.0"
                 xmlns:content="http://purl.org/rss/1.0/modules/content/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>Example</title>
                <item>
                  <title>First post</title>
                  <link>https://example.com/1</link>
                  <guid>https://example.com/1</guid>
                  <pubDate>Mon, 16 Jun 2026 10:00:00 GMT</pubDate>
                  <dc:creator>Jane</dc:creator>
                  <description>A summary</description>
                  <content:encoded><![CDATA[<p>Full body</p>]]></content:encoded>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val items = parser.parse(SourceType.RSS, rss)
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("https://example.com/1", item.guid)
        assertEquals("First post", item.title)
        assertEquals("https://example.com/1", item.link)
        assertEquals("Jane", item.author)
        assertTrue(item.publishedAt?.contains("2026") == true)
        assertEquals("A summary", item.summary)
        assertTrue(item.content?.contains("Full body") == true, "content:encoded should be captured")
    }

    @Test
    fun `parses ATOM entries with id and alternate link`() {
        val atom = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Example</title>
              <entry>
                <title>Atom post</title>
                <link href="https://example.com/a1" rel="alternate"/>
                <id>tag:example.com,2026:a1</id>
                <updated>2026-06-16T10:00:00Z</updated>
                <author><name>Bob</name></author>
                <summary>Atom summary</summary>
                <content type="html">Atom body</content>
              </entry>
            </feed>
        """.trimIndent()

        val items = parser.parse(SourceType.ATOM, atom)
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("tag:example.com,2026:a1", item.guid)
        assertEquals("Atom post", item.title)
        assertEquals("https://example.com/a1", item.link)
        assertEquals("Bob", item.author)
        assertTrue(item.publishedAt?.contains("2026") == true)
        assertTrue(item.content?.contains("Atom body") == true)
    }

    @Test
    fun `parses JSON Feed items with content_html and author`() {
        val jsonFeed = """
            {
              "version": "https://jsonfeed.org/version/1.1",
              "title": "Example",
              "items": [
                {
                  "id": "jf1",
                  "title": "JSON post",
                  "url": "https://example.com/jf1",
                  "date_published": "2026-06-16T10:00:00Z",
                  "summary": "JF summary",
                  "content_html": "<p>JF body</p>",
                  "author": { "name": "Carol" }
                }
              ]
            }
        """.trimIndent()

        val items = parser.parse(SourceType.JSON_FEED, jsonFeed)
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("jf1", item.guid)
        assertEquals("JSON post", item.title)
        assertEquals("https://example.com/jf1", item.link)
        assertEquals("Carol", item.author)
        assertTrue(item.content?.contains("JF body") == true)
    }

    @Test
    fun `API type is not handled by the envelope parser`() {
        assertFailsWith<IllegalArgumentException> { parser.parse(SourceType.API, "{}") }
    }

    @Test
    fun `RSS media-content wins over an image enclosure, and audio enclosures never count`() {
        val rss = """
            <?xml version="1.0"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <item>
                  <guid>g1</guid><title>With media</title>
                  <media:content url="https://cdn.x/media.jpg" medium="image"/>
                  <enclosure url="https://cdn.x/enclosure.jpg" type="image/jpeg"/>
                </item>
                <item>
                  <guid>g2</guid><title>Enclosure only</title>
                  <enclosure url="https://cdn.x/pod.mp3" type="audio/mpeg"/>
                  <enclosure url="https://cdn.x/still.png" type="image/png"/>
                </item>
                <item>
                  <guid>g3</guid><title>No image</title>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val items = parser.parse(SourceType.RSS, rss)

        assertEquals("https://cdn.x/media.jpg", items[0].imageUrl)
        assertEquals("https://cdn.x/still.png", items[1].imageUrl)
        assertEquals(null, items[2].imageUrl)
    }

    @Test
    fun `RSS media-credit is captured for attribution`() {
        val rss = """
            <?xml version="1.0"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <item>
                  <guid>g1</guid><title>Credited</title>
                  <media:content url="https://cdn.x/hero.jpg" medium="image">
                    <media:credit scheme="urn:ebu">Jane Photographer / Example Wire</media:credit>
                  </media:content>
                </item>
                <item>
                  <guid>g2</guid><title>Uncredited</title>
                  <media:content url="https://cdn.x/other.jpg" medium="image"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val items = parser.parse(SourceType.RSS, rss)

        assertEquals("Jane Photographer / Example Wire", items[0].imageCredit)
        assertEquals(null, items[1].imageCredit)
    }

    @Test
    fun `ATOM media-thumbnail and image enclosure links are captured`() {
        val atom = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
              <entry>
                <id>a1</id><title>Thumb</title>
                <media:thumbnail url="https://cdn.x/thumb.jpg"/>
              </entry>
              <entry>
                <id>a2</id><title>Enclosure</title>
                <link rel="enclosure" type="image/webp" href="https://cdn.x/hero.webp"/>
              </entry>
            </feed>
        """.trimIndent()

        val items = parser.parse(SourceType.ATOM, atom)

        assertEquals("https://cdn.x/thumb.jpg", items[0].imageUrl)
        assertEquals("https://cdn.x/hero.webp", items[1].imageUrl)
    }

    @Test
    fun `JSON Feed image and banner_image are captured in that order`() {
        val json = """
            {"version": "https://jsonfeed.org/version/1.1", "title": "x", "items": [
                {"id": "j1", "title": "Image", "image": "https://cdn.x/i.png", "banner_image": "https://cdn.x/b.png"},
                {"id": "j2", "title": "Banner", "banner_image": "https://cdn.x/b2.png"},
                {"id": "j3", "title": "None"}
            ]}
        """.trimIndent()

        val items = parser.parse(SourceType.JSON_FEED, json)

        assertEquals("https://cdn.x/i.png", items[0].imageUrl)
        assertEquals("https://cdn.x/b2.png", items[1].imageUrl)
        assertEquals(null, items[2].imageUrl)
    }
}
