package io.github.obelisk121.trimodel.ide;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import io.github.obelisk121.trimodel.model.ChatMessage;
import io.github.obelisk121.trimodel.model.OrchestrationMode;
import io.github.obelisk121.trimodel.ui.TriModelToolWindowPanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Project-scoped service tracking conversation history and bridging editor actions to the Tool Window.
 */
@Service(Service.Level.PROJECT)
public final class TriModelProjectService {
    public static final String TOOL_WINDOW_ID = "Tri-Model AI";
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final Project project;
    private final List<ChatMessage> history = Collections.synchronizedList(new ArrayList<>());
    private volatile TriModelToolWindowPanel toolWindowPanel;

    public TriModelProjectService(Project project) {
        this.project = project;
    }

    public static TriModelProjectService getInstance(Project project) {
        return project.getService(TriModelProjectService.class);
    }

    public void attachPanel(TriModelToolWindowPanel panel) {
        this.toolWindowPanel = panel;
    }

    public TriModelToolWindowPanel getPanel() {
        return toolWindowPanel;
    }

    public List<ChatMessage> getHistorySnapshot() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public void appendTurn(String userPrompt, String assistantOutput) {
        synchronized (history) {
            if (userPrompt != null && !userPrompt.isBlank()) {
                history.add(ChatMessage.user(userPrompt));
            }
            if (assistantOutput != null && !assistantOutput.isBlank()) {
                // Keep conversation history compact for follow-up turns
                String compact = assistantOutput.length() > 16000
                        ? assistantOutput.substring(0, 16000) + "\n...[truncated in history]..."
                        : assistantOutput;
                history.add(ChatMessage.assistant(compact));
            }
            while (history.size() > MAX_HISTORY_MESSAGES) {
                history.remove(0);
            }
        }
    }

    public void clearHistory() {
        history.clear();
    }

    /**
     * Activates the Tri-Model AI tool window, selects the requested mode, attaches editor context,
     * pre-fills the prompt, and optionally runs immediately.
     */
    public void openWithPreset(
            OrchestrationMode mode,
            String prompt,
            boolean selectionOnly,
            boolean includeDiagnostics,
            boolean autoRun
    ) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return;
        }
        toolWindow.activate(() -> {
            TriModelToolWindowPanel panel = this.toolWindowPanel;
            if (panel != null) {
                panel.applyQuickActionPreset(mode, prompt, selectionOnly, includeDiagnostics, autoRun);
            }
        });
    }
}
