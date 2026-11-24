package io.github.obelisk121.trimodel.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import io.github.obelisk121.trimodel.ide.TriModelProjectService;
import io.github.obelisk121.trimodel.model.OrchestrationMode;
import org.jetbrains.annotations.NotNull;

/**
 * Immediately runs a parallel Tri-Model Deep Code Audit on the active file or selection.
 */
public final class DeepAnalyzeAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean hasSelection = editor != null && editor.getSelectionModel().hasSelection();
        String prompt = "Perform a deep tri-model engineering audit covering logic bugs, edge cases, type safety, "
                + "security vulnerabilities, error handling, architecture, and performance, and propose concrete fixes.";
        TriModelProjectService.getInstance(project)
                .openWithPreset(OrchestrationMode.DEEP_ANALYSIS, prompt, hasSelection, true, true);
    }
}
