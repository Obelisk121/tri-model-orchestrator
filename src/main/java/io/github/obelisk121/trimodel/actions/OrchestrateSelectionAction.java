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
 * Opens Tri-Model AI in Role Pipeline mode with current editor file or selection attached.
 */
public final class OrchestrateSelectionAction extends DumbAwareAction {
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
        String prompt = hasSelection
                ? "Refactor, improve, and harden the selected code while preserving its public contract."
                : "Design and implement the requested improvements for the attached file.";
        TriModelProjectService.getInstance(project)
                .openWithPreset(OrchestrationMode.PIPELINE, prompt, hasSelection, true, false);
    }
}
