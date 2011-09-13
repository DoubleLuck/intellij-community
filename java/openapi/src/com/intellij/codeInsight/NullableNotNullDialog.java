/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight;

import com.intellij.ide.util.ClassFilter;
import com.intellij.ide.util.TreeClassChooser;
import com.intellij.ide.util.TreeClassChooserFactory;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBList;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * User: anna
 * Date: 1/25/11
 */
public class NullableNotNullDialog extends DialogWrapper {
  private static final Icon ADD_ICON = IconLoader.getIcon("/general/add.png");
  private static final Icon REMOVE_ICON = IconLoader.getIcon("/general/remove.png");
  private static final Icon SAVE_ICON = IconLoader.getIcon("/ide/defaultProfile.png");

  private final Project myProject;
  private AnnoPanel myNullablePanel;
  private AnnoPanel myNotNullPanel;

  public NullableNotNullDialog(Project project) {
    super(project, true);
    myProject = project;
    init();
    setTitle("Nullable/NotNull configuration");
  }


  @Override
  protected JComponent createCenterPanel() {
    final NullableNotNullManager manager = NullableNotNullManager.getInstance(myProject);
    final Splitter splitter = new Splitter(true);
    myNullablePanel =
      new AnnoPanel("Nullable annotations", manager.getDefaultNullable(), manager.getNullables(), NullableNotNullManager.DEFAULT_NULLABLES);
    splitter.setFirstComponent(myNullablePanel);
    myNotNullPanel =
      new AnnoPanel("NotNull annotations", manager.getDefaultNotNull(), manager.getNotNulls(), NullableNotNullManager.DEFAULT_NOT_NULLS);
    splitter.setSecondComponent(myNotNullPanel);
    splitter.setHonorComponentsMinimumSize(true);
    splitter.setPreferredSize(new Dimension(300, 400));
    return splitter;
  }

  @Override
  protected void doOKAction() {
    final NullableNotNullManager manager = NullableNotNullManager.getInstance(myProject);

    manager.setNotNulls(myNotNullPanel.getAnns());
    manager.setDefaultNotNull(myNotNullPanel.getDefaultAnn());

    manager.setNullables(myNullablePanel.getAnns());
    manager.setDefaultNullable(myNullablePanel.getDefaultAnn());

    super.doOKAction();
  }

  private class AnnoPanel extends JPanel {
    private String myDefaultAnn;
    private final String[] myDefaultAnns;
    private final JBList myList;

    private AnnoPanel(final String title, final String defaultAnn, final List<String> anns, final String[] defaultAnns) {
      super(new GridBagLayout());
      myDefaultAnn = defaultAnn;
      myDefaultAnns = defaultAnns;
      myList = new JBList(anns);
      myList.setCellRenderer(new DefaultListCellRenderer(){
        @Override
        public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
          final Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
          if (Comparing.strEqual((String)value, myDefaultAnn)) {
            setIcon(IconLoader.getIcon("/diff/currentLine.png"));
            setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD));
          }
          else {
            setIcon(EmptyIcon.ICON_16);
          }
          setText((String)value);
          return component;
        }
      });
      myList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      myList.setSelectedValue(defaultAnn, true);
      final DefaultActionGroup group = new DefaultActionGroup();
      group.add(new AnAction("Add", "Add", ADD_ICON) {
        {
          registerCustomShortcutSet(CommonShortcuts.INSERT, myList);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
          chooseAnnotation(title, myList, null);
        }
      });
      group.add(new AnAction("Delete", "Delete", REMOVE_ICON) {
        {
          registerCustomShortcutSet(CommonShortcuts.DELETE, myList);
        }

        @Override
        public void update(AnActionEvent e) {
          final Object selectedValue = myList.getSelectedValue();
          final boolean enabled = selectedValue != null && ArrayUtil.find(myDefaultAnns, selectedValue) == -1;
          e.getPresentation().setEnabled(enabled);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
          final String selectedValue = (String)myList.getSelectedValue();
          if (selectedValue == null) return;
          if (myDefaultAnn.equals(selectedValue)) myDefaultAnn = myDefaultAnns[0];
          ((DefaultListModel)myList.getModel()).removeElement(selectedValue);
        }
      });
      group.add(new AnAction("Make default", "Default", SAVE_ICON) {
        @Override
        public void update(AnActionEvent e) {
          final String selectedValue = (String)myList.getSelectedValue();
          final boolean enabled = selectedValue != null && !Comparing.strEqual(myDefaultAnn, selectedValue);
          e.getPresentation().setEnabled(enabled);
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
          final String selectedValue = (String)myList.getSelectedValue();
          if (selectedValue == null) return;
          myDefaultAnn = selectedValue;
          final DefaultListModel model = (DefaultListModel)myList.getModel();

          // to show the new default value in the ui
          model.setElementAt(myList.getSelectedValue(), myList.getSelectedIndex());
        }
      });
      GridBagConstraints gc = new GridBagConstraints(0, 0, 1, 1, 1, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL, new Insets(5,0,0,0), 0, 0);
      add(new TitledSeparator(title), gc);
      gc.gridy++;
      gc.insets.top = 0;
      add(ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, group, true).getComponent(), gc);
      gc.gridy++;
      gc.weighty = 1;
      gc.fill = GridBagConstraints.BOTH;
      final JScrollPane scrollPane = ScrollPaneFactory.createScrollPane(myList);
      scrollPane.setMinimumSize(new Dimension(250, 100));
      add(scrollPane, gc);
    }

    private void chooseAnnotation(String title, JBList list, PsiClass initial) {
      final TreeClassChooser chooser = TreeClassChooserFactory.getInstance(myProject)
        .createNoInnerClassesScopeChooser("Choose " + title, GlobalSearchScope.allScope(myProject), new ClassFilter() {
          @Override
          public boolean isAccepted(PsiClass aClass) {
            return aClass.isAnnotationType();
          }
        }, initial);
      chooser.showDialog();
      final PsiClass selected = chooser.getSelected();
      if (selected != null) {
        final String qualifiedName = selected.getQualifiedName();
        ((DefaultListModel)list.getModel()).addElement(qualifiedName);
      }
    }

    public String getDefaultAnn() {
      return myDefaultAnn;
    }

    public Object[] getAnns() {
      return ((DefaultListModel)myList.getModel()).toArray();
    }
  }
}
