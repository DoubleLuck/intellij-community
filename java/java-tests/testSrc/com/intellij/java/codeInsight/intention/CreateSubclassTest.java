// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInsight.intention;

import com.intellij.codeInsight.intention.impl.CreateSubclassAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.PathManagerEx;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.refactoring.LightMultiFileTestCase;
import com.intellij.util.ui.UIUtil;


public class CreateSubclassTest extends LightMultiFileTestCase {
 
  public void testGenerics() {
    doTest();
  }

  public void testImports() {
    doTest();
  }

  public void testInnerClassImplement() {
    doTestInner();
  }

  public void testInnerClass() {
    doTestInner();
  }

  public void testSealed() {
    doTest();
  }

  public void testSealedWithSameFileInheritors() {
    doTestSameFileClass();
  }

  private void doTestInner() {
    doTest(() -> {
      PsiClass superClass = myFixture.findClass("Test");
      final PsiClass inner = superClass.findInnerClassByName("Inner", false);
      assertNotNull(inner);
      CreateSubclassAction.createInnerClass(inner);
      UIUtil.dispatchAllInvocationEvents();
    });
  }

  private void doTestSameFileClass() {
    doTest(() -> {
      PsiClass superClass = myFixture.findClass("Superclass");
      ApplicationManager.getApplication().invokeLater(
        () -> CreateSubclassAction.createSameFileClass("Subclass", superClass));
      UIUtil.dispatchAllInvocationEvents();
    });
  }

  private void doTest() {
    doTest(() -> {
      PsiDirectory root = getPsiManager().findDirectory(myFixture.getTempDirFixture().findOrCreateDir(""));
      PsiClass superClass = myFixture.findClass("Superclass");
      ApplicationManager.getApplication().invokeLater(
        () -> CreateSubclassAction.createSubclass(superClass, root, "Subclass"));
      UIUtil.dispatchAllInvocationEvents();
    });
  }

  @Override
  protected String getTestDataPath() {
    return PathManagerEx.getTestDataPath() + "/codeInsight/createSubclass/";
  }
}
