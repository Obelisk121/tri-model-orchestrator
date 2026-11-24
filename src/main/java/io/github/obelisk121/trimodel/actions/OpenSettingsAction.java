package io.github.obelisk121.trimodel.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import io.github.obelisk121.trimodel.settings.TriModelConfigurable;
import org.jetbrains.annotations.NotNull;

/**
 * Opens Tri-Model AI Orchestrator Settings directly from the menu.
 */
public final class OpenSettingsAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.getProject(), TriModelConfigurable.class);
    }
}
