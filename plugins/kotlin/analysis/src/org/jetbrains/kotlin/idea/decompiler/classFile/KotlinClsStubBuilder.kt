// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.decompiler.classFile

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.compiled.ClsStubBuilder
import com.intellij.psi.impl.compiled.ClassFileStubBuilder
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.util.indexing.FileContent
import org.jetbrains.kotlin.SpecialJvmAnnotations
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.idea.caches.IDEKotlinBinaryClassCache
import org.jetbrains.kotlin.idea.decompiler.stubBuilder.*
import org.jetbrains.kotlin.load.kotlin.*
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolver
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.stubs.KotlinStubVersions
import org.jetbrains.kotlin.resolve.constants.ClassLiteralValue
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.constants.KClassValue
import org.jetbrains.kotlin.serialization.deserialization.getClassId
import org.jetbrains.kotlin.storage.LockBasedStorageManager

open class KotlinClsStubBuilder : ClsStubBuilder() {
    override fun getStubVersion() = ClassFileStubBuilder.STUB_VERSION + KotlinStubVersions.CLASSFILE_STUB_VERSION

    override fun buildFileStub(content: FileContent): PsiFileStub<*>? {
        val virtualFile = content.file

        if (isKotlinInternalCompiledFile(virtualFile, content.content)) {
            return null
        }

        if (isVersioned(virtualFile)) {
            // Kotlin can't build stubs for versioned class files, because list of versioned inner classes
            // might be incomplete
            return null
        }

        return doBuildFileStub(virtualFile, content.content)
    }

    private fun doBuildFileStub(file: VirtualFile, fileContent: ByteArray): PsiFileStub<KtFile>? {
        val kotlinClass = IDEKotlinBinaryClassCache.getInstance().getKotlinBinaryClass(file, fileContent)
            ?: error("Can't find binary class for Kotlin file: $file")
        val header = kotlinClass.classHeader
        val classId = kotlinClass.classId
        val packageFqName = header.packageName?.let { FqName(it) } ?: classId.packageFqName

        if (!header.metadataVersion.isCompatible()) {
            return createIncompatibleAbiVersionFileStub()
        }

        val components = createStubBuilderComponents(file, packageFqName, fileContent)
        if (header.kind == KotlinClassHeader.Kind.MULTIFILE_CLASS) {
            val partFiles = findMultifileClassParts(file, classId, header.multifilePartNames)
            return createMultifileClassStub(header, partFiles, classId.asSingleFqName(), components)
        }

        val annotationData = header.data
        if (annotationData == null) {
            LOG.error("Corrupted kotlin header for file ${file.name}")
            return null
        }
        val strings = header.strings
        if (strings == null) {
            LOG.error("String table not found in file ${file.name}")
            return null
        }
        return when (header.kind) {
            KotlinClassHeader.Kind.CLASS -> {
                if (classId.isLocal) return null
                val (nameResolver, classProto) = JvmProtoBufUtil.readClassDataFrom(annotationData, strings)
                if (Flags.VISIBILITY.get(classProto.flags) == ProtoBuf.Visibility.LOCAL) {
                    // Older Kotlin compiler versions didn't put 'INNERCLASS' attributes in some cases (e.g. for cross-inline lambdas),
                    // so 'ClassFileViewProvider.isInnerClass()' pre-check won't find them (EA-105730).
                    // Example: `Timer().schedule(1000) { foo () }`.
                    return null
                }

                val context = components.createContext(nameResolver, packageFqName, TypeTable(classProto.typeTable))
                createTopLevelClassStub(classId, classProto, KotlinJvmBinarySourceElement(kotlinClass), context, header.isScript)
            }
            KotlinClassHeader.Kind.FILE_FACADE -> {
                val (nameResolver, packageProto) = JvmProtoBufUtil.readPackageDataFrom(annotationData, strings)
                val context = components.createContext(nameResolver, packageFqName, TypeTable(packageProto.typeTable))
                val fqName = header.packageName?.let { ClassId(FqName(it), classId.relativeClassName, classId.isLocal).asSingleFqName() }
                    ?: classId.asSingleFqName()
                createFileFacadeStub(packageProto, fqName, context)
            }
            else -> throw IllegalStateException("Should have processed " + file.path + " with header $header")
        }
    }

    private fun createStubBuilderComponents(file: VirtualFile, packageFqName: FqName, fileContent: ByteArray): ClsStubBuilderComponents {
        val classFinder = DirectoryBasedClassFinder(file.parent!!, packageFqName)
        val classDataFinder = DirectoryBasedDataFinder(classFinder, LOG)
        val annotationLoader = AnnotationLoaderForClassFileStubBuilder(classFinder, file, fileContent)
        return ClsStubBuilderComponents(classDataFinder, annotationLoader, file)
    }

    companion object {
        val LOG = Logger.getInstance(KotlinClsStubBuilder::class.java)

        // Archive separator + META-INF + versions
        private val VERSIONED_PATH_MARKER = "!/META-INF/versions/"

        fun isVersioned(virtualFile: VirtualFile): Boolean {
            return virtualFile.path.contains(VERSIONED_PATH_MARKER)
        }
    }
}

class AnnotationLoaderForClassFileStubBuilder(
    kotlinClassFinder: KotlinClassFinder,
    private val cachedFile: VirtualFile,
    private val cachedFileContent: ByteArray
) : AbstractBinaryClassAnnotationAndConstantLoader<ClassId, Unit>(LockBasedStorageManager.NO_LOCKS, kotlinClassFinder) {

    override fun getCachedFileContent(kotlinClass: KotlinJvmBinaryClass): ByteArray? {
        if ((kotlinClass as? VirtualFileKotlinClass)?.file == cachedFile) {
            return cachedFileContent
        }
        return null
    }

    override fun loadTypeAnnotation(proto: ProtoBuf.Annotation, nameResolver: NameResolver): ClassId = nameResolver.getClassId(proto.id)

    override fun loadConstant(desc: String, initializer: Any) = null

    override fun transformToUnsignedConstant(constant: Unit) = null

    override fun loadAnnotation(
        annotationClassId: ClassId, source: SourceElement, result: MutableList<ClassId>
    ): KotlinJvmBinaryClass.AnnotationArgumentVisitor? {
        if (annotationClassId != SpecialJvmAnnotations.JAVA_LANG_ANNOTATION_REPEATABLE) {
            result += annotationClassId
            return null
        }

        return object : KotlinJvmBinaryClass.AnnotationArgumentVisitor {
            private val arguments = mutableMapOf<Name, ConstantValue<*>>()
            override fun visitClassLiteral(name: Name?, value: ClassLiteralValue) {
                if (name != null)
                    arguments[name] = KClassValue(value)
            }

            override fun visitEnd() {
                if (!isRepeatableWithImplicitContainer(annotationClassId, arguments)) {
                    result.add(annotationClassId)
                }
            }

            override fun visit(name: Name?, value: Any?) = Unit
            override fun visitEnum(name: Name?, enumClassId: ClassId, enumEntryName: Name) = Unit
            override fun visitArray(name: Name?): KotlinJvmBinaryClass.AnnotationArrayArgumentVisitor? = null
            override fun visitAnnotation(name: Name?, classId: ClassId): KotlinJvmBinaryClass.AnnotationArgumentVisitor? = null
        }
    }

    override fun loadAnnotationMethodDefaultValue(
        annotationClass: KotlinJvmBinaryClass,
        methodSignature: MemberSignature,
        visitResult: (Unit) -> Unit
    ): KotlinJvmBinaryClass.AnnotationArgumentVisitor? = null
}
