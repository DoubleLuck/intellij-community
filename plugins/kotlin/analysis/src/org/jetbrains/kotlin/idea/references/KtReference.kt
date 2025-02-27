// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.references

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.safeAnalyze
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.util.application.runWithCancellationCheck
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import java.util.*

interface KtDescriptorsBasedReference : KtReference {
    override val resolver get() = KotlinDescriptorsBasedReferenceResolver

    fun resolveToDescriptors(bindingContext: BindingContext): Collection<DeclarationDescriptor> = getTargetDescriptors(bindingContext)

    fun getTargetDescriptors(context: BindingContext): Collection<DeclarationDescriptor>

    override fun isReferenceTo(element: PsiElement): Boolean = matchesTarget(element)
}

fun KtReference.resolveToDescriptors(bindingContext: BindingContext): Collection<DeclarationDescriptor> {
    if (this !is KtDescriptorsBasedReference) {
        error("Reference $this should be KtDescriptorsBasedReference but was ${this::class}")
    }

    return resolveToDescriptors(bindingContext)
}


object KotlinDescriptorsBasedReferenceResolver : ResolveCache.PolyVariantResolver<KtReference> {
    class KotlinResolveResult(element: PsiElement) : PsiElementResolveResult(element)

    private fun resolveToPsiElements(ref: KtDescriptorsBasedReference): Collection<PsiElement> {
        val bindingContext = ref.element.safeAnalyze(BodyResolveMode.PARTIAL)
        if (bindingContext == BindingContext.EMPTY) return emptySet()
        return resolveToPsiElements(ref, bindingContext, ref.getTargetDescriptors(bindingContext))
    }

    private fun resolveToPsiElements(
        ref: KtDescriptorsBasedReference,
        context: BindingContext,
        targetDescriptors: Collection<DeclarationDescriptor>
    ): Collection<PsiElement> {
        if (targetDescriptors.isNotEmpty()) {
            return targetDescriptors.flatMap { target -> resolveToPsiElements(ref, target) }.toSet()
        }

        val labelTargets = getLabelTargets(ref, context)
        if (labelTargets != null) {
            return labelTargets
        }

        return Collections.emptySet()
    }

    private fun resolveToPsiElements(
        ref: KtDescriptorsBasedReference,
        targetDescriptor: DeclarationDescriptor
    ): Collection<PsiElement> = if (targetDescriptor is PackageViewDescriptor) {
        val psiFacade = JavaPsiFacade.getInstance(ref.element.project)
        val fqName = targetDescriptor.fqName.asString()
        listOfNotNull(psiFacade.findPackage(fqName))
    } else {
        DescriptorToSourceUtilsIde.getAllDeclarations(ref.element.project, targetDescriptor, ref.element.resolveScope)
    }

    private fun getLabelTargets(ref: KtDescriptorsBasedReference, context: BindingContext): Collection<PsiElement>? {
        val reference = ref.element as? KtReferenceExpression ?: return null
        val labelTarget = context[BindingContext.LABEL_TARGET, reference]
        if (labelTarget != null) {
            return listOf(labelTarget)
        }

        return context[BindingContext.AMBIGUOUS_LABEL_TARGET, reference]
    }

    override fun resolve(ref: KtReference, incompleteCode: Boolean): Array<ResolveResult> = runWithCancellationCheck {
        val resolveToPsiElements = resolveToPsiElements(ref as KtDescriptorsBasedReference)
        resolveToPsiElements.map { KotlinResolveResult(it) }.toTypedArray()
    }
}
