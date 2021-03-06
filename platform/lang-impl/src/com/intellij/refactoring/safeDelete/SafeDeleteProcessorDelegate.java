// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.refactoring.safeDelete;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * @author yole
 */
public interface SafeDeleteProcessorDelegate {
  ExtensionPointName<SafeDeleteProcessorDelegate> EP_NAME = ExtensionPointName.create("com.intellij.refactoring.safeDeleteProcessor");

  boolean handlesElement(PsiElement element);
  @Nullable
  NonCodeUsageSearchInfo findUsages(@NotNull PsiElement element, PsiElement @NotNull [] allElementsToDelete, @NotNull List<UsageInfo> result);

  /**
   * Called before the refactoring dialog is shown. Returns the list of elements for which the
   * usages should be searched for the specified element selected by the user for deletion.
   * May show UI to ask the user if some additional elements should be deleted along with the
   * specified selected element.
   *
   * @param element an element selected for deletion.
   * @param allElementsToDelete all elements selected for deletion.
   * @return additional elements to search for usages, or null if the user has cancelled the refactoring.
   */
  @Nullable
  Collection<? extends PsiElement> getElementsToSearch(@NotNull PsiElement element, @NotNull Collection<PsiElement> allElementsToDelete);

  @Nullable
  Collection<PsiElement> getAdditionalElementsToDelete(@NotNull PsiElement element, @NotNull Collection<PsiElement> allElementsToDelete, final boolean askUser);

  @Nullable
  Collection<@Nls String> findConflicts(@NotNull PsiElement element, PsiElement @NotNull [] allElementsToDelete);

  /**
   * Called after the user has confirmed the refactoring. Can filter out some of the usages
   * found by the refactoring. May show UI to ask the user if some of the usages should
   * be excluded.
   *
   * @param project the project where the refactoring happens.
   * @param usages all usages to be processed by the refactoring. 
   * @return the filtered list of usages, or null if the user has cancelled the refactoring.
   */
  UsageInfo @Nullable [] preprocessUsages(Project project, UsageInfo[] usages);

  void prepareForDeletion(PsiElement element) throws IncorrectOperationException;

  boolean isToSearchInComments(final PsiElement element);

  void setToSearchInComments(final PsiElement element, boolean enabled);

  boolean isToSearchForTextOccurrences(final PsiElement element);

  void setToSearchForTextOccurrences(final PsiElement element, boolean enabled);
}
