package bosca.bml.ide

import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider

/**
 * Code-style surface for BML — the indent options the formatter reads. Defaults mirror the
 * house style of the existing sites (2-space markup indent, 4-space wrapped attributes).
 */
class BmlLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): Language = BmlLanguage

    override fun customizeDefaults(
        commonSettings: CommonCodeStyleSettings,
        indentOptions: CommonCodeStyleSettings.IndentOptions,
    ) {
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 2
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun getCodeSample(settingsType: SettingsType): String = """
        <page route="/fruit/{id}" requireAuth>
          <script server provides="fruits">
            listOf("alpha", "beta")
          </script>

          <h1>Fruits</h1>
          <for fruit in fruits>
            <if fruit == "alpha">
              <p class="first">{ fruit }</p>
            <else>
              <p>{ fruit }</p>
            </if>
          </for>

          <style scoped>
            .first { color: var(--accent); }
          </style>
        </page>
    """.trimIndent()
}
