package bosca.bml.ide

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/** `.bml` file type. Registered via the `com.intellij.fileType` extension point. */
object BmlFileType : LanguageFileType(BmlLanguage) {
    override fun getName(): String = "BML"
    override fun getDescription(): String = "Bosca Markup Language"
    override fun getDefaultExtension(): String = "bml"
    override fun getIcon(): Icon = BmlIcons.FILE
}
