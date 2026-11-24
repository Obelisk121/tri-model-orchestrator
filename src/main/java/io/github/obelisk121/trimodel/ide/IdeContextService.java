package io.github.obelisk121.trimodel.ide;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.obelisk121.trimodel.model.ContextAttachment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts active editor code, selections, live PyCharm diagnostics, and attached project files.
 */
public final class IdeContextService {
    private static final int MAX_FILE_CHARS = 180_000;
    private static final int MAX_DIAGNOSTICS = 30;

    private IdeContextService() {
    }

    public static ContextAttachment captureActiveEditor(
            Project project,
            boolean selectionOnlyIfPresent,
            boolean includeDiagnostics
    ) {
        if (project == null || project.isDisposed()) {
            return null;
        }
        return ApplicationManager.getApplication().runReadAction((Computable<ContextAttachment>) () -> {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) {
                return null;
            }
            Document doc = editor.getDocument();
            VirtualFile vFile = FileDocumentManager.getInstance().getFile(doc);
            String relPath = relativePath(project, vFile);
            String lang = detectLanguage(vFile);

            List<String> diagnostics = includeDiagnostics ? collectDiagnostics(project, doc) : List.of();

            SelectionModel sel = editor.getSelectionModel();
            if (selectionOnlyIfPresent && sel.hasSelection()) {
                String selectedText = sel.getSelectedText();
                if (selectedText != null && !selectedText.isBlank()) {
                    int startLine = doc.getLineNumber(sel.getSelectionStart()) + 1;
                    int endLine = doc.getLineNumber(sel.getSelectionEnd()) + 1;
                    return new ContextAttachment(relPath, lang, truncate(selectedText), true, startLine, endLine, diagnostics);
                }
            }

            String fullText = doc.getText();
            int totalLines = Math.max(1, doc.getLineCount());
            return new ContextAttachment(relPath, lang, truncate(fullText), false, 1, totalLines, diagnostics);
        });
    }

    public static ContextAttachment readVirtualFile(Project project, VirtualFile file, boolean includeDiagnostics) {
        if (project == null || file == null || file.isDirectory() || !file.isValid()) {
            return null;
        }
        if (file.getFileType().isBinary()) {
            return null;
        }
        return ApplicationManager.getApplication().runReadAction((Computable<ContextAttachment>) () -> {
            try {
                Document doc = FileDocumentManager.getInstance().getDocument(file);
                String text;
                List<String> diagnostics = List.of();
                if (doc != null) {
                    text = doc.getText();
                    if (includeDiagnostics) {
                        diagnostics = collectDiagnostics(project, doc);
                    }
                } else {
                    byte[] bytes = file.contentsToByteArray();
                    text = new String(bytes, StandardCharsets.UTF_8);
                }
                int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
                return new ContextAttachment(
                        relativePath(project, file),
                        detectLanguage(file),
                        truncate(text),
                        false,
                        1,
                        lines,
                        diagnostics
                );
            } catch (Exception e) {
                return null;
            }
        });
    }

    public static String relativePath(Project project, VirtualFile file) {
        if (file == null) {
            return "active_editor.py";
        }
        VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
        if (baseDir != null) {
            String rel = VfsUtilCore.getRelativePath(file, baseDir, '/');
            if (rel != null && !rel.isBlank()) {
                return rel;
            }
        }
        return file.getName();
    }

    private static List<String> collectDiagnostics(Project project, Document doc) {
        List<String> out = new ArrayList<>();
        try {
            List<HighlightInfo> highlights = DaemonCodeAnalyzerImpl.getHighlights(doc, HighlightSeverity.WARNING, project);
            for (HighlightInfo info : highlights) {
                if (out.size() >= MAX_DIAGNOSTICS) break;
                String desc = info.getDescription();
                if (desc == null || desc.isBlank()) continue;
                int line = info.startOffset >= 0 && info.startOffset <= doc.getTextLength()
                        ? doc.getLineNumber(info.startOffset) + 1
                        : 1;
                String severity = info.getSeverity() != null ? info.getSeverity().getName() : "WARNING";
                out.add("Line " + line + " [" + severity + "]: " + desc.trim());
            }
        } catch (Exception ignored) {
            // Daemon highlights may be unavailable during dumb mode
        }
        return out;
    }

    private static String detectLanguage(VirtualFile file) {
        if (file == null) return "python";
        String ext = file.getExtension();
        if (ext == null) return "";
        return switch (ext.toLowerCase()) {
            case "py", "pyi" -> "python";
            case "js", "mjs", "cjs" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "sql" -> "sql";
            case "json" -> "json";
            case "yml", "yaml" -> "yaml";
            case "toml" -> "toml";
            case "html", "htm" -> "html";
            case "css", "scss" -> "css";
            case "sh", "zsh", "bash" -> "bash";
            case "md" -> "markdown";
            default -> ext.toLowerCase();
        };
    }

    private static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_FILE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_FILE_CHARS) + "\n# [truncated due to size limit]\n";
    }
}
