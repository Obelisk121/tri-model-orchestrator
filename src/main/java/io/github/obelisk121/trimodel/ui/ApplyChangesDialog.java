package io.github.obelisk121.trimodel.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import io.github.obelisk121.trimodel.ide.CodeApplyService;
import io.github.obelisk121.trimodel.model.ProposedFileChange;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/**
 * Confirmation dialog allowing selective multi-file application with per-file PyCharm Diff preview.
 */
public final class ApplyChangesDialog extends DialogWrapper {

    private final Project project;
    private final List<ProposedFileChange> changes;
    private final List<JBCheckBox> checkboxes = new ArrayList<>();

    public ApplyChangesDialog(Project project, List<ProposedFileChange> changes) {
        super(project, true);
        this.project = project;
        this.changes = changes;
        setTitle("Apply Tri-Model AI Code Changes");
        setOKButtonText("Apply Selected Changes");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setPreferredSize(new Dimension(560, 320));

        JBLabel intro = new JBLabel(
                "<html>Select the proposed files to write into your PyCharm project. "
                        + "Changes are applied atomically and can be undone with <b>Cmd/Ctrl+Z</b>.</html>");
        root.add(intro, BorderLayout.NORTH);

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBorder(JBUI.Borders.empty(6));

        checkboxes.clear();
        for (ProposedFileChange change : changes) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setBorder(JBUI.Borders.empty(4, 2));

            String statusTag;
            if (!change.hasExplicitPath()) {
                statusTag = "[ACTIVE EDITOR]";
            } else {
                VirtualFile existing = CodeApplyService.resolveExistingFile(project, change);
                statusTag = existing != null ? "[UPDATE]" : "[NEW FILE]";
            }

            int lines = change.code().split("\n", -1).length;
            JBCheckBox cb = new JBCheckBox(statusTag + " " + change.displayName() + " (" + lines + " lines)", true);
            checkboxes.add(cb);

            JButton diffBtn = new JButton("Preview Diff");
            diffBtn.addActionListener(e -> CodeApplyService.showDiffForChange(project, change));

            row.add(cb, BorderLayout.CENTER);
            row.add(diffBtn, BorderLayout.EAST);
            listPanel.add(row);
        }

        root.add(new JBScrollPane(listPanel), BorderLayout.CENTER);
        return root;
    }

    public List<ProposedFileChange> getSelectedChanges() {
        List<ProposedFileChange> selected = new ArrayList<>();
        for (int i = 0; i < Math.min(changes.size(), checkboxes.size()); i++) {
            if (checkboxes.get(i).isSelected()) {
                selected.add(changes.get(i));
            }
        }
        return selected;
    }
}
