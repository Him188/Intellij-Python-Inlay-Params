@file:Suppress("UnstableApiUsage")

package space.whitememory.pythoninlayparams.types.hints

import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PyNames
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.codeInsight.typing.PyTypingTypeProvider
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.types.*
import space.whitememory.pythoninlayparams.types.variables.PythonVariablesInlayTypeHintsProvider

enum class HintResolver {

    GLOBALS_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val assignedValue = PyUtil.peelArgument(element.findAssignedValue())
            if (assignedValue !is PyCallExpression) return true

            return assignedValue.callee?.name !in builtinMethods
        }
    },

    GENERAL_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val assignedValue = PyUtil.peelArgument(element.findAssignedValue())

            if (assignedValue is PyPrefixExpression) {
                if (assignedValue.operator == PyTokenTypes.AWAIT_KEYWORD) {
                    return shouldShowTypeHint(element, typeEvalContext)
                }

                return shouldShowTypeHint(assignedValue.operand as PyElement, typeEvalContext)
            }

            return shouldShowTypeHint(element, typeEvalContext)
        }
    },

    TYPING_MODULE_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val assignedValue = PyUtil.peelArgument(element.findAssignedValue())

            // Handle case `var = async_func()` without `await` keyword
            if (assignedValue is PyCallExpression) return true
            // Handle case 'var = await async_func()` which return `Coroutine` inside
            if (assignedValue is PyPrefixExpression && assignedValue.operator == PyTokenTypes.AWAIT_KEYWORD) return true

            if (assignedValue is PySubscriptionExpression) {
                assignedValue.rootOperand.reference?.resolve()?.let {
                    return !isElementInsideTypingModule(it as PyElement)
                }
            }

            if (assignedValue is PyReferenceExpression) {
                assignedValue.reference.resolve()?.let {
                    return !isElementInsideTypingModule(it as PyElement)
                }
            }

            if (typeAnnotation is PyClassType && isElementInsideTypingModule(typeAnnotation.pyClass)) return false

            return true
        }
    },

    GENERIC_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val assignedValue = PyUtil.peelArgument(element.findAssignedValue())

            assignedValue?.let {
                typeEvalContext.getType(element.findAssignedValue() as PyTypedElement)?.let {
                    return it !is PyTypeVarType
                }
            }

            return true
        }
    },

    EXCEPTION_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean = PsiTreeUtil.getParentOfType(element, PyExceptPart::class.java) == null
    },

    CLASS_ATTRIBUTE_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings): Boolean =
            !settings.showClassAttributeHints

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean = !PyUtil.isClassAttribute(element)
    },

    UNION_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            if (typeAnnotation is PyUnionType) {
                return typeAnnotation.members.any { type ->
                    resolveEnabled(settings).all {
                        shouldShowTypeHint(element, type, typeEvalContext, settings)
                    }
                }
            }

            return true
        }
    },

    CLASS_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val assignedValue = PyUtil.peelArgument(element.findAssignedValue())

            if (typeAnnotation !is PyClassType) return true

            val resolvedClasses = typeAnnotation.pyClass.getSuperClassTypes(typeEvalContext)

            if (resolvedClasses.isNotEmpty()) {
                return resolvedClasses.all {
                    shouldShowTypeHint(element, it, typeEvalContext, settings)
                }
            }

            if (
                assignedValue is PyCallExpression
                && isClassLikeCallExpression(assignedValue, typeAnnotation)
            ) {
                // Handle case like User().get_name() and list()
                if (typeAnnotation.isBuiltin || assignedValue.callee?.reference?.resolve() is PyFunction) {
                    return resolveEnabled(settings)
                        .filter { it != CLASS_HINT }
                        .all { it.shouldShowTypeHint(element, typeAnnotation, typeEvalContext, settings) }
                }

                return false
            }

            return true
        }
    },

    CONDITIONAL_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val assignmentValue = PyUtil.peelArgument(element.findAssignedValue())

            if (assignmentValue is PyConditionalExpression) {
                return resolveExpression(ExpressionOperands.fromPyExpression(assignmentValue)!!, typeEvalContext)
            }

            if (assignmentValue is PyBinaryExpression) {
                return resolveExpression(ExpressionOperands.fromPyExpression(assignmentValue)!!, typeEvalContext)
            }

            return true
        }

        private fun resolveExpression(
            expressionOperands: ExpressionOperands,
            typeEvalContext: TypeEvalContext,
        ): Boolean {
            if (isLiteralExpression(expressionOperands.leftOperand) && isLiteralExpression(expressionOperands.rightOperand)) {
                return false
            }

            if (expressionOperands.leftOperand is PyCallExpression && expressionOperands.rightOperand is PyCallExpression) {
                val leftType = typeEvalContext.getType(expressionOperands.leftOperand)
                val rightType = typeEvalContext.getType(expressionOperands.rightOperand)
                val isFalsePartClass =
                    leftType is PyClassType && isClassLikeCallExpression(expressionOperands.leftOperand, leftType)
                val isTruePartClass =
                    rightType is PyClassType && isClassLikeCallExpression(expressionOperands.rightOperand, rightType)

                if (isFalsePartClass && isTruePartClass) return false
            }

            return true
        }
    },

    COMPREHENSION_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val assignmentValue = PyUtil.peelArgument(element.findAssignedValue())

            if (assignmentValue is PyComprehensionElement) {
                if (typeAnnotation is PyCollectionType) {
                    return typeAnnotation.elementTypes.filterNotNull().isNotEmpty()
                }

                if (assignmentValue is PySetCompExpression || assignmentValue is PyDictCompExpression) return false
            }

            return true
        }
    },

    SET_HINT {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        private val collectionNames = setOf("frozenset", PyNames.SET)

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val assignmentValue = PyUtil.peelArgument(element.findAssignedValue())

            if (assignmentValue !is PyCallExpression) return true

            if (typeAnnotation is PyNoneLiteralExpression) return true

            val resolvedClassName = getCalledClassName(assignmentValue) ?: return true

            if (!collectionNames.contains(resolvedClassName)) {
                return resolvedClassName != typeAnnotation?.name
            }

            val collectionType = (typeAnnotation as? PyCollectionType) ?: return false
            return collectionType.isBuiltin && collectionType.elementTypes.filterNotNull().isNotEmpty()
        }
    },

    LITERAL_EXPRESSION {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val assignedValue = PyUtil.peelArgument(element.findAssignedValue())

            if (isLiteralExpression(assignedValue)) {
                if ((typeAnnotation is PyTypedDictType || (typeAnnotation is PyClassType && typeAnnotation.pyClass.qualifiedName == PyNames.DICT)) && assignedValue is PySequenceExpression) {
                    // Handle case when dict contains all literal expressions
                    if (assignedValue.elements.isNotEmpty() && assignedValue.elements.all {
                            it is PyKeyValueExpression && isLiteralExpression(it.value)
                        }) {
                        return false
                    }

                    if (typeAnnotation.name == "dict") return false

                    return !assignedValue.isEmpty
                }

                return try {
                    !(assignedValue as PySequenceExpression).isEmpty
                } catch (e: Exception) {
                    false
                }
            }

            return true
        }
    },

    TUPLE_TYPE {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            if (typeAnnotation !is PyTupleType) return true
            if (typeAnnotation.elementTypes.filterNotNull().isEmpty()) return false

            val assignedValue = PyUtil.peelArgument(element.findAssignedValue())

            if (assignedValue !is PyTupleExpression) return true

            return assignedValue.elements.any { !isLiteralExpression(it) }
        }
    },

    ENUM_TYPE {
        override fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings) = true

        override fun shouldShowTypeHint(
            element: PyTargetExpression,
            typeAnnotation: PyType?,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val assignedValue = PyUtil.peelArgument(element.findAssignedValue())

            if (assignedValue !is PyReferenceExpression) return true

            val resolvedExpression = assignedValue.reference.resolve() ?: return true

            if (resolvedExpression is PyTargetExpression) {
                return resolveEnabled(settings)
                    .filter { it != ENUM_TYPE }
                    .all { it.shouldShowTypeHint(resolvedExpression, typeAnnotation, typeEvalContext, settings) }
            }

            return true
        }
    };

    abstract fun shouldShowTypeHint(
        element: PyTargetExpression,
        typeAnnotation: PyType?,
        typeEvalContext: TypeEvalContext,
        settings: PythonVariablesInlayTypeHintsProvider.Settings,
    ): Boolean

    abstract fun isApplicable(settings: PythonVariablesInlayTypeHintsProvider.Settings): Boolean

    companion object {
        val builtinMethods = setOf("globals", "locals")

        fun resolve(
            element: PyTargetExpression,
            typeEvalContext: TypeEvalContext,
            settings: PythonVariablesInlayTypeHintsProvider.Settings,
        ): Boolean {
            val typeAnnotation = getExpressionAnnotationType(element, typeEvalContext)

            return resolveEnabled(settings).any {
                !it.shouldShowTypeHint(element, typeAnnotation, typeEvalContext, settings)
            }
        }

        private fun resolveEnabled(
            settings: PythonVariablesInlayTypeHintsProvider.Settings
        ): List<HintResolver> = entries.filter { it.isApplicable(settings) }

        private fun isLiteralExpression(element: PyExpression?): Boolean {
            return element is PySequenceExpression || element is PyLiteralExpression || element is PySetCompExpression
        }

        private fun isElementInsideTypingModule(element: PyElement): Boolean {
            if (element is PyQualifiedNameOwner) {
                element.qualifiedName?.let {
                    return it.startsWith("${PyTypingTypeProvider.TYPING}.") || it.startsWith("typing_extensions.")
                }
            }

            return false
        }

        private fun isClassLikeCallExpression(expression: PyCallExpression, typeAnnotation: PyClassType): Boolean {
            val callee = expression.callee ?: return false
            val resolved = callee.reference?.resolve()

            if (resolved is PyClass) return true
            if (resolved is PyFunction && typeAnnotation.isBuiltin) return true

            if (callee is PyQualifiedExpression) {
                val qualifierResolved = (callee.qualifier as? PyReferenceExpression)?.reference?.resolve()
                if (qualifierResolved is PyClass && qualifierResolved.name == typeAnnotation.pyClass.name) {
                    return true
                }
            }

            return false
        }

        private fun getCalledClassName(expression: PyCallExpression): String? {
            val callee = expression.callee ?: return null
            val resolved = callee.reference?.resolve()

            if (resolved is PyClass) return resolved.name

            if (callee is PyQualifiedExpression) {
                val qualifierResolved = (callee.qualifier as? PyReferenceExpression)?.reference?.resolve()
                if (qualifierResolved is PyClass) return qualifierResolved.name
            }

            return callee.name
        }

        fun getExpressionAnnotationType(element: PyElement, typeEvalContext: TypeEvalContext): PyType? {
            if (element is PyFunction) {
                if (element.isAsync && !element.isGenerator) {
                    return element.getReturnStatementType(typeEvalContext)
                }

                return typeEvalContext.getReturnType(element)
            }
            if (element is PyTargetExpression) return typeEvalContext.getType(element)

            return null
        }

        fun shouldShowTypeHint(
            element: PyElement,
            typeEvalContext: TypeEvalContext,
        ): Boolean {
            if (element.name == PyNames.UNDERSCORE) return false
            if (element is PyTargetExpression && element.isQualified) return false

            val typeAnnotation = getExpressionAnnotationType(element, typeEvalContext)

            if (
                typeAnnotation == null
                || (element is PyFunction && typeAnnotation is PyNoneLiteralExpression)
                || ((element is PyFunction || element is PyTargetExpression) && (element as PyTypeCommentOwner).typeCommentAnnotation != null)
                || (element is PyAnnotationOwner && element.annotation != null)
            ) {
                return false
            }

            if (typeAnnotation is PyUnionType) {
                return !typeAnnotation.members.all {
                    PyTypeChecker.isUnknown(it, false, typeEvalContext) || (it is PyNoneLiteralExpression || it == null)
                }
            }

            if (PyTypeChecker.isUnknown(typeAnnotation, false, typeEvalContext)) return false

            return true
        }
    }
}
