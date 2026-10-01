package bosca.messages

/**
 * The URL-bearing head CSS. Emails render outside any origin, so these rules can't live in the
 * shell's static `<style>` (a raw region — no interpolation): they build from the absolute asset
 * base ([bosca.bml.message.BmlMessageContext.assetsUrl]) at render time instead.
 */
object Theme {

    /**
     * Geist / Geist Mono (OFL) as progressive enhancement: clients that support webfonts
     * (Apple Mail, iOS Mail, Samsung) render the brand type; Gmail + Outlook-Windows strip
     * `@font-face` and fall back to the system stacks declared inline throughout the markup.
     * Both families ship as variable fonts, so one file per family covers every weight.
     */
    fun fontFaces(assets: String): String = """
        <style>
          @font-face { font-family:'Geist'; font-style:normal; font-weight:100 900; src:url('$assets/fonts/geist-variable.woff2') format('woff2'); }
          @font-face { font-family:'Geist Mono'; font-style:normal; font-weight:100 900; src:url('$assets/fonts/geist-mono-variable.woff2') format('woff2'); }
        </style>
    """.trimIndent()
}
