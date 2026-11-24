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
 * Opens Tri-Model AI in Parallel Council mode so Claude, ChatGPT, and Gemini collaborate and debate.
 */
public final class CouncilConsultAction extends DumbAwareAction {
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
        String prompt = "Analyze the attached code independently, debate trade-offs and edge cases across all three models, "
                + "and synthesize the best consensus solution.";
        TriModelProjectService.getInstance(project)
                .openWithPreset(OrchestrationMode.PARALLEL_COUNCIL, prompt, hasSelection, true, false);
    }
}
