package bosca.bml.library

/** Per-page library defaults and ordered global module bridges selected during compilation. */
data class LinkedBmlClientAssets(
    val styles: Map<String, List<String>> = emptyMap(),
    val scripts: Map<String, List<String>> = emptyMap(),
)
