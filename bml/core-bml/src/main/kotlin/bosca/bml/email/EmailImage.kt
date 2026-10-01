package bosca.bml.email

/**
 * A bundled image an email template embeds via `<img bml-inline src="…">`: the
 * rendered HTML references it as `src="cid:[cid]"` and the hosting layer attaches the bundle
 * asset at [source] (a `public/`-relative path inside the project jar) as an inline
 * `multipart/related` part with that Content-ID. Embedding beats remote asset URLs where it
 * matters: the image shows even before the client loads remote images.
 */
data class EmailImage(
    val cid: String,
    val source: String,
)
