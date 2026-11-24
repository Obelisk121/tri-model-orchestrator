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
 * Runs the Iterative Code & Dual-Review Loop to diagnose and fix bugs and PyCharm diagnostics.
 */
public final class FixBugsAction extends DumbAwareAction {
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
        String prompt = "Identify and fix all bugs, runtime hazards, type issues, and PyCharm IDE diagnostics in the attached code. "
                + "Verify edge cases and return complete, ready-to-apply code.";
        TriModelProjectService.getInstance(project)
                .openWithPreset(OrchestrationMode.ITERATIVE_LOOP, prompt, hasSelection, true, true);
    }
}
