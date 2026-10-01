package bosca.bml.ide

import com.intellij.lang.Commenter

/** Ctrl+/ comments BML with `{# … #}` block comments. */
class BmlCommenter : Commenter {
    override fun getLineCommentPrefix(): String? = null
    override fun getBlockCommentPrefix(): String = "{#"
    override fun getBlockCommentSuffix(): String = "#}"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
