// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.PlatformIcons
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.core.*
import org.jetbrains.kotlin.idea.imports.importableFqName
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.modalityModifier
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.typeUtil.supertypes

class AddFunctionToSupertypeFix private constructor(
    element: KtNamedFunction,
    private val functions: List<FunctionData>
) : KotlinQuickFixAction<KtNamedFunction>(element), LowPriorityAction {

    init {
        assert(functions.isNotEmpty())
    }

    private class FunctionData(
        val signaturePreview: String,
        val sourceCode: String,
        val targetClass: KtClass
    )

    override fun getText(): String =
        functions.singleOrNull()?.let { actionName(it) } ?: KotlinBundle.message("fix.add.function.supertype.text")

    override fun getFamilyName() = KotlinBundle.message("fix.add.function.supertype.family")

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        CommandProcessor.getInstance().runUndoTransparentAction {
            if (functions.size == 1 || editor == null || !editor.component.isShowing) {
                addFunction(functions.first(), project)
            } else {
                JBPopupFactory.getInstance().createListPopup(createFunctionPopup(project)).showInBestPositionFor(editor)
            }
        }
    }

    private fun addFunction(functionData: FunctionData, project: Project) {
        project.executeWriteCommand(KotlinBundle.message("fix.add.function.supertype.progress")) {
            element?.removeDefaultValues()

            val classBody = functionData.targetClass.getOrCreateBody()

            val functionElement = KtPsiFactory(project).createFunction(functionData.sourceCode)
            val insertedFunctionElement = classBody.addBefore(functionElement, classBody.rBrace) as KtNamedFunction

            ShortenReferences.DEFAULT.process(insertedFunctionElement)
            val modifierToken = insertedFunctionElement.modalityModifier()?.node?.elementType as? KtModifierKeywordToken
                ?: return@executeWriteCommand
            if (insertedFunctionElement.implicitModality() == modifierToken) {
                RemoveModifierFix(insertedFunctionElement, modifierToken, true).invoke()
            }
        }
    }

    private fun KtNamedFunction.removeDefaultValues() {
        valueParameters.forEach {
            it.defaultValue?.delete()
            it.equalsToken?.delete()
        }
    }

    private fun createFunctionPopup(project: Project): ListPopupStep<*> {
        return object : BaseListPopupStep<FunctionData>(KotlinBundle.message("fix.add.function.supertype.choose.type"), functions) {
            override fun isAutoSelectionEnabled() = false

            override fun onChosen(selectedValue: FunctionData, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    addFunction(selectedValue, project)
                }
                return PopupStep.FINAL_CHOICE
            }

            override fun getIconFor(value: FunctionData) = PlatformIcons.FUNCTION_ICON
            override fun getTextFor(value: FunctionData) = actionName(value)
        }
    }

    @Nls
    private fun actionName(functionData: FunctionData): String =
        KotlinBundle.message(
            "fix.add.function.supertype.add.to",
            functionData.signaturePreview, functionData.targetClass.name.toString()
        )

    companion object : KotlinSingleIntentionActionFactory() {
        override fun createAction(diagnostic: Diagnostic): IntentionAction? {
            val function = diagnostic.psiElement as? KtNamedFunction ?: return null

            val descriptors = generateFunctionsToAdd(function)
            if (descriptors.isEmpty()) return null

            val project = diagnostic.psiFile.project
            val functionData = descriptors.mapNotNull { createFunctionData(it, project) }
            if (functionData.isEmpty()) return null

            return AddFunctionToSupertypeFix(function, functionData)
        }

        private fun createFunctionData(functionDescriptor: FunctionDescriptor, project: Project): FunctionData? {
            val classDescriptor = functionDescriptor.containingDeclaration as ClassDescriptor
            var sourceCode = IdeDescriptorRenderers.SOURCE_CODE.withDefaultValueOption(project).render(functionDescriptor)
            if (classDescriptor.kind != ClassKind.INTERFACE && functionDescriptor.modality != Modality.ABSTRACT) {
                val returnType = functionDescriptor.returnType
                sourceCode += if (returnType == null || !KotlinBuiltIns.isUnit(returnType)) {
                    val bodyText = getFunctionBodyTextFromTemplate(
                        project,
                        TemplateKind.FUNCTION,
                        functionDescriptor.name.asString(),
                        functionDescriptor.returnType?.let { IdeDescriptorRenderers.SOURCE_CODE.renderType(it) } ?: "Unit",
                        classDescriptor.importableFqName
                    )
                    "{\n$bodyText\n}"
                } else {
                    "{}"
                }
            }

            val targetClass = DescriptorToSourceUtilsIde.getAnyDeclaration(project, classDescriptor) as? KtClass ?: return null
            return FunctionData(
                IdeDescriptorRenderers.SOURCE_CODE_SHORT_NAMES_NO_ANNOTATIONS.withDefaultValueOption(project).render(functionDescriptor),
                sourceCode,
                targetClass
            )
        }

        private fun DescriptorRenderer.withDefaultValueOption(project: Project): DescriptorRenderer {
            return withOptions {
                this.defaultParameterValueRenderer = {
                    OptionalParametersHelper.defaultParameterValueExpression(it, project)?.text
                        ?: error("value parameter renderer shouldn't be called when there is no value to render")
                }
            }
        }

        private fun generateFunctionsToAdd(functionElement: KtNamedFunction): List<FunctionDescriptor> {
            val functionDescriptor = functionElement.resolveToDescriptorIfAny(BodyResolveMode.FULL) ?: return emptyList()

            val containingClass = functionDescriptor.containingDeclaration as? ClassDescriptor ?: return emptyList()

            // TODO: filter out impossible supertypes (for example when argument's type isn't visible in a superclass).
            return getSuperClasses(containingClass)
                .asSequence()
                .filterNot { KotlinBuiltIns.isAnyOrNullableAny(it.defaultType) }
                .map { generateFunctionSignatureForType(functionDescriptor, it) }
                .toList()
        }

        private fun MutableList<KotlinType>.sortSubtypesFirst(): List<KotlinType> {
            val typeChecker = KotlinTypeChecker.DEFAULT
            for (i in 1 until size) {
                val currentType = this[i]
                for (j in 0 until i) {
                    if (typeChecker.isSubtypeOf(currentType, this[j])) {
                        this.removeAt(i)
                        this.add(j, currentType)
                        break
                    }
                }
            }
            return this
        }

        private fun getSuperClasses(classDescriptor: ClassDescriptor): List<ClassDescriptor> {
            val supertypes = classDescriptor.defaultType.supertypes().toMutableList().sortSubtypesFirst()
            return supertypes.mapNotNull { it.constructor.declarationDescriptor as? ClassDescriptor }
        }

        private fun generateFunctionSignatureForType(
            functionDescriptor: FunctionDescriptor,
            typeDescriptor: ClassDescriptor
        ): FunctionDescriptor {
            // TODO: support for generics.

            val modality = if (typeDescriptor.kind == ClassKind.INTERFACE || typeDescriptor.modality == Modality.SEALED) {
                Modality.ABSTRACT
            } else {
                typeDescriptor.modality
            }

            return functionDescriptor.copy(
                typeDescriptor,
                modality,
                functionDescriptor.visibility,
                CallableMemberDescriptor.Kind.DECLARATION,
                /* copyOverrides = */ false
            )
        }
    }
}
