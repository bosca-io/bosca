package bosca.bml.ide

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

class BmlPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, BmlLanguage) {
    override fun getFileType(): FileType = BmlFileType
    override fun toString(): String = "BML File"
}
