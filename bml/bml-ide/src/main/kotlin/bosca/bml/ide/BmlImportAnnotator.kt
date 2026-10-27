package bosca.bml.ide

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Reports compiler import diagnostics and a source-attachment retry path while preserving binary completion. */
class BmlImportAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element is PsiFile) {
            BmlComponentDecls.scope(element).problems.forEach {
                if (it.file == element) {
                    holder.newAnnotation(HighlightSeverity.ERROR, it.message).range(it.range).create()
                } else {
                    holder.newAnnotation(HighlightSeverity.ERROR, "${it.file.name}: ${it.message}")
                        .range(TextRange(0, minOf(1, element.textLength))).create()
                }
            }
        } else if (element is BmlTagNameElement) {
            val scope = BmlComponentDecls.scope(element.containingFile)
            scope.declarations.firstOrNull { it.tag == element.text }?.navigationProblem?.let {
                holder.newAnnotation(HighlightSeverity.WEAK_WARNING, it).range(element).create()
            }
        }
    }
}
