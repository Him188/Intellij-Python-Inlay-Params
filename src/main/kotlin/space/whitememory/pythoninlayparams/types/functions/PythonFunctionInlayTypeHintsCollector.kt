package space.whitememory.pythoninlayparams.types.functions

import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.endOffset
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.psi.PyElement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyParameterList
import com.jetbrains.python.psi.PyStatementList
import space.whitememory.pythoninlayparams.types.AbstractPythonInlayTypeHintsCollector
import space.whitememory.pythoninlayparams.types.hints.HintResolver

@Suppress("UnstableApiUsage")
class PythonFunctionInlayTypeHintsCollector(editor: Editor) :
    AbstractPythonInlayTypeHintsCollector(editor) {

    override val textBeforeTypeHint = "->"

    override fun validateExpression(element: PsiElement): Boolean {
        // Not a function or has only def keyword
        if (element !is PyFunction || element.nameNode == null) return false

        val colonToken = TokenSet.create(PyTokenTypes.COLON)
        if (element.node.getChildren(colonToken).isEmpty()) return false

        return PsiTreeUtil.getChildOfType(element, PyStatementList::class.java)
            ?.statements
            ?.isNotEmpty() == true
    }

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (!element.isValid || element.project.isDefault) return false
        if (!validateExpression(element)) return true

        val typeEvalContext = getTypeEvalContext(editor, element)
        if (!HintResolver.shouldShowTypeHint(element as PyFunction, typeEvalContext)) return true

        try {
            renderTypeHint(element, typeEvalContext, sink)
        } catch (_: Exception) {
            return true
        }

        return true
    }

    override fun displayTypeHint(element: PyElement, sink: InlayHintsSink, hintName: InlayPresentation) {
        val statementList = PsiTreeUtil.getChildOfType(element, PyParameterList::class.java) ?: return
        sink.addInlineElement(
            statementList.endOffset,
            false,
            factory.roundWithBackground(factory.seq(factory.smallText("$textBeforeTypeHint "), hintName)),
            false
        )
    }
}
