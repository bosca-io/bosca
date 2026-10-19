package bosca.backup

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Checks the native custom-format header independently of source or PostgreSQL client availability. */
internal fun requireNativePostgresDump(path: Path) {
    check(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) >= 5) {
        "pg_dump did not produce a native database artifact"
    }
    Files.newInputStream(path).use {
        check(it.readNBytes(5).contentEquals("PGDMP".toByteArray(Charsets.US_ASCII))) {
            "pg_dump output is not a custom-format archive"
        }
    }
}
