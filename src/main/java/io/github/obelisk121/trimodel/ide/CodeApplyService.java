package io.github.obelisk121.trimodel.ide;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.obelisk121.trimodel.model.ProposedFileChange;
import io.github.obelisk121.trimodel.orchestration.CodeChangeExtractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Native PyCharm Diff preview and atomic Undo-capable code application service.
 */
public final class CodeApplyService {
    private static final String NOTIFICATION_GROUP = "Tri-Model AI Orchestrator";

    private CodeApplyService() {
    }

    public static void showDiffForChange(Project project, ProposedFileChange change) {
        if (project == null || change == null) return;
        DiffContentFactory factory = DiffContentFactory.getInstance();

        VirtualFile existing = resolveExistingFile(project, change);
        FileType fileType = detectFileType(change, existing);

        DiffContent leftContent;
        String leftTitle;
        if (existing != null) {
            leftContent = factory.create(project, existing);
            leftTitle = "Current: " + IdeContextService.relativePath(project, existing);
        } else {
            Editor activeEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (!change.hasExplicitPath() && activeEditor != null) {
                SelectionModel sel = activeEditor.getSelectionModel();
                if (sel.hasSelection() && sel.getSelectedText() != null) {
                    leftContent = factory.create(project, sel.getSelectedText(), fileType);
                    leftTitle = "Current Selection";
                } else {
                    leftContent = factory.create(project, activeEditor.getDocument());
                    leftTitle = "Current Active Editor";
                }
            } else {
                leftContent = factory.create(project, "", fileType);
                leftTitle = "New File: " + change.displayName();
            }
        }

        DiffContent rightContent = factory.create(project, StringUtil.convertLineSeparators(change.code()), fileType);
        String rightTitle = "Proposed (" + change.sourceLabel() + ")";
        String title = "Tri-Model AI Diff — " + change.displayName();

        SimpleDiffRequest request = new SimpleDiffRequest(title, leftContent, rightContent, leftTitle, rightTitle);
        DiffManager.getInstance().showDiff(project, request);
    }

    public static boolean replaceActiveEditorOrSelection(Project project, String code) {
        if (project == null || code == null) return false;
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            notify(project, "No active editor is open to apply code.", NotificationType.WARNING);
            return false;
        }
        Document doc = editor.getDocument();
        SelectionModel sel = editor.getSelectionModel();
        boolean hadSelection = sel.hasSelection();
        int start = hadSelection ? sel.getSelectionStart() : 0;
        int end = hadSelection ? sel.getSelectionEnd() : doc.getTextLength();

        String normalized = StringUtil.convertLineSeparators(code);
        WriteCommandAction.runWriteCommandAction(project, "Apply Tri-Model AI Code", null, () -> {
            doc.replaceString(start, end, normalized);
        });
        notify(project,
                hadSelection ? "Replaced selected code in active editor (Cmd/Ctrl+Z to undo)."
                        : "Replaced active editor contents (Cmd/Ctrl+Z to undo).",
                NotificationType.INFORMATION);
        return true;
    }

    public static List<String> applyFileChanges(Project project, List<ProposedFileChange> changes) {
        List<String> appliedPaths = new ArrayList<>();
        if (project == null || changes == null || changes.isEmpty()) {
            return appliedPaths;
        }
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir == null) {
            notify(project, "Cannot resolve PyCharm project root directory.", NotificationType.ERROR);
            return appliedPaths;
        }

        List<String> errors = new ArrayList<>();
        List<VirtualFile> filesToOpen = new ArrayList<>();

        WriteCommandAction.runWriteCommandAction(project, "Apply Tri-Model AI Changes", null, () -> {
            for (ProposedFileChange change : changes) {
                try {
                    String normalizedCode = StringUtil.convertLineSeparators(change.code());
                    if (!change.hasExplicitPath()) {
                        Editor active = FileEditorManager.getInstance(project).getSelectedTextEditor();
                        if (active != null) {
                            Document doc = active.getDocument();
                            SelectionModel sel = active.getSelectionModel();
                            if (sel.hasSelection()) {
                                doc.replaceString(sel.getSelectionStart(), sel.getSelectionEnd(), normalizedCode);
                            } else {
                                doc.setText(normalizedCode);
                            }
                            VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
                            appliedPaths.add(vf != null ? IdeContextService.relativePath(project, vf) : "(active editor)");
                        } else {
                            errors.add("Skipped unnamed snippet (no active editor open).");
                        }
                        continue;
                    }

                    String safePath = CodeChangeExtractor.sanitizePath(change.filePath());
                    if (safePath == null) {
                        errors.add("Rejected unsafe file path: " + change.filePath());
                        continue;
                    }

                    VirtualFile target = findOrCreateFile(baseDir, safePath);
                    Document doc = FileDocumentManager.getInstance().getDocument(target);
                    if (doc != null) {
                        doc.setText(normalizedCode);
                        FileDocumentManager.getInstance().saveDocument(doc);
                    } else {
                        VfsUtil.saveText(target, normalizedCode);
                    }
                    appliedPaths.add(safePath);
                    filesToOpen.add(target);
                } catch (Exception ex) {
                    errors.add("Failed writing " + change.displayName() + ": " + ex.getMessage());
                }
            }
        });

        for (VirtualFile vf : filesToOpen) {
            FileEditorManager.getInstance(project).openFile(vf, true);
        }

        if (!appliedPaths.isEmpty()) {
            notify(project,
                    "Applied " + appliedPaths.size() + " change(s): " + String.join(", ", appliedPaths),
                    NotificationType.INFORMATION);
        }
        if (!errors.isEmpty()) {
            notify(project, String.join("\n", errors), NotificationType.WARNING);
        }
        return appliedPaths;
    }

    public static VirtualFile resolveExistingFile(Project project, ProposedFileChange change) {
        if (project == null || change == null || !change.hasExplicitPath()) {
            return null;
        }
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir == null) return null;
        String safe = CodeChangeExtractor.sanitizePath(change.filePath());
        if (safe == null) return null;
        return baseDir.findFileByRelativePath(safe);
    }

    private static VirtualFile findOrCreateFile(VirtualFile baseDir, String relativePath) throws IOException {
        VirtualFile existing = baseDir.findFileByRelativePath(relativePath);
        if (existing != null) {
            return existing;
        }
        int lastSlash = relativePath.lastIndexOf('/');
        VirtualFile parent = baseDir;
        String fileName = relativePath;
        if (lastSlash > 0) {
            String dirPath = relativePath.substring(0, lastSlash);
            fileName = relativePath.substring(lastSlash + 1);
            parent = VfsUtil.createDirectoryIfMissing(baseDir, dirPath);
        }
        return parent.createChildData(CodeApplyService.class, fileName);
    }

    private static FileType detectFileType(ProposedFileChange change, VirtualFile existing) {
        if (existing != null) {
            return existing.getFileType();
        }
        if (change.hasExplicitPath()) {
            return FileTypeManager.getInstance().getFileTypeByFileName(change.filePath());
        }
        String ext = switch (change.language().toLowerCase()) {
            case "python", "py" -> "py";
            case "javascript", "js" -> "js";
            case "typescript", "ts" -> "ts";
            case "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "sql" -> "sql";
            case "java" -> "java";
            case "kotlin", "kt" -> "kt";
            default -> "py";
        };
        return FileTypeManager.getInstance().getFileTypeByExtension(ext);
    }

    public static void notify(Project project, String message, NotificationType type) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification("Tri-Model AI Orchestrator", message, type)
                    .notify(project);
        } catch (Exception ignored) {
        }
    }
}
