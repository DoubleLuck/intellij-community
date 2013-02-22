package com.jetbrains.python.refactoring;

import com.intellij.codeInsight.unwrap.UnwrapHandler;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.fixtures.PyTestCase;
import com.jetbrains.python.psi.LanguageLevel;

import java.util.List;

/**
 * User : ktisha
 */
public class PyUnwrapperTest extends PyTestCase {

  public void testIfUnwrap()                          throws Throwable {doTest();}
  public void testIfUnwrapEmpty()                     throws Throwable {doNegativeTest();}
  public void testIfUnwrapMultipleStatements()        throws Throwable {doTest();}
  public void testWhileUnwrap()                       throws Throwable {doTest();}
  public void testWhileUnwrapEmpty()                  throws Throwable {doNegativeTest();}
  public void testWhileUnwrapMultipleStatements()     throws Throwable {doTest();}

  public void testIfWithElseUnwrap()                  throws Throwable {doTest();}
  public void testIfInWhileUnwrap()                   throws Throwable {doTest();}
  public void testWhileInIfUnwrap()                   throws Throwable {doTest();}
  public void testIfInIfUnwrap()                      throws Throwable {doTest();}
  public void testWhileInWhileUnwrap()                throws Throwable {doTest();}

  public void testElseInIfUnwrap()                    throws Throwable {doTest(1);}
  public void testElseInIfDelete()                    throws Throwable {doTest();}
  public void testInnerElseUnwrap()                   throws Throwable {doTest(1);}

  public void testElIfUnwrap()                        throws Throwable {doTest();}
  public void testElIfDelete()                        throws Throwable {doTest(1);}

  public void testTryUnwrap()                         throws Throwable {doTest();}
  public void testTryFinallyUnwrap()                  throws Throwable {doTest();}
  public void testTryElseFinallyUnwrap()              throws Throwable {doTest();}

  public void testForUnwrap()                         throws Throwable {doTest();}

  public void testWithUnwrap()                        throws Throwable {doTest(LanguageLevel.PYTHON32);}

  public void testEndOfStatementUnwrap()              throws Throwable {doTest();}
  public void testEndOfStatementNextLineUnwrap()      throws Throwable {doNegativeTest();}

  public void testIfInElifBranchUnwrap()              throws Throwable {doNegativeTest(PyBundle.message("unwrap.if"));}

  private void doTest() {
    doTest(0);
  }

  private void doTest(LanguageLevel languageLevel) {
    setLanguageLevel(languageLevel);
    try {
      doTest(0);
    }
    finally {
      setLanguageLevel(null);
    }
  }

  private void doTest(final int option) {
    String before = "refactoring/unwrap/" + getTestName(true) + "_before.py";
    String after = "refactoring/unwrap/" + getTestName(true) + "_after.py";
    myFixture.configureByFile(before);
    UnwrapHandler h = new UnwrapHandler() {
      @Override
      protected void selectOption(List<AnAction> options, Editor editor, PsiFile file) {
        assertTrue("No available options to unwrap", !options.isEmpty());
        options.get(option).actionPerformed(null);
      }
    };
    h.invoke(myFixture.getProject(), myFixture.getEditor(), myFixture.getFile());

    myFixture.checkResultByFile(after, true);
   }


  private void doNegativeTest() {
    String before = "refactoring/unwrap/" + getTestName(true) + "_before.py";
    myFixture.configureByFile(before);
    UnwrapHandler h = new UnwrapHandler() {
      @Override
      protected void selectOption(List<AnAction> options, Editor editor, PsiFile file) {
        assertEmpty(options);
      }
    };
    h.invoke(myFixture.getProject(), myFixture.getEditor(), myFixture.getFile());
  }

  private void doNegativeTest(final String optionName) {
    String before = "refactoring/unwrap/" + getTestName(true) + "_before.py";
    myFixture.configureByFile(before);
    UnwrapHandler h = new UnwrapHandler() {
      @Override
      protected void selectOption(List<AnAction> options, Editor editor, PsiFile file) {
        for (AnAction option : options) {
          assertFalse("\"" + optionName  + "\" is available to unwrap ", option.toString().contains(optionName));
        }
      }
    };
    h.invoke(myFixture.getProject(), myFixture.getEditor(), myFixture.getFile());
  }

}
