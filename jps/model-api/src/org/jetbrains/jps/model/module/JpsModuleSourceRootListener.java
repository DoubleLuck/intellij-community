/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package org.jetbrains.jps.model.module;

import org.jetbrains.annotations.NotNull;

import java.util.EventListener;

/**
 * @deprecated modifications of JpsModel were never fully supported, and they won't be since JpsModel will be superseded by {@link com.intellij.workspaceModel.storage.WorkspaceEntityStorage the workspace model}.
 */
@SuppressWarnings("DeprecatedIsStillUsed")
@Deprecated
public interface JpsModuleSourceRootListener extends EventListener {
  void sourceRootAdded(@NotNull JpsModuleSourceRoot root);
  void sourceRootRemoved(@NotNull JpsModuleSourceRoot root);
  void sourceRootChanged(@NotNull JpsModuleSourceRoot root);
}
