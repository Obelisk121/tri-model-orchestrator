package io.github.obelisk121.trimodel.model;

import java.util.List;

/**
 * Code file or selection context attached from PyCharm.
 */
public record ContextAttachment(
        String relativePath,
        String language,
        String content,
        boolean selectionOnly,
        int startLine,
        int endLine,
        List<String> diagnostics
) {
    public ContextAttachment {
        relativePath = relativePath == null ? "untitled" : relativePath;
        language = language == null ? "" : language;
        content = content == null ? "" : content;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public String formatForPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("### CONTEXT FILE: ").append(relativePath);
        if (selectionOnly && startLine > 0) {
            sb.append(" (selected lines ").append(startLine).append("-").append(endLine).append(")");
        }
        sb.append("\n");
        if (!diagnostics.isEmpty()) {
            sb.append("IDE Diagnostics / Problems:\n");
            for (String diag : diagnostics) {
                sb.append("- ").append(diag).append("\n");
            }
        }
        sb.append("```").append(language).append("\n");
        sb.append(content);
        if (!content.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append("```\n");
        return sb.toString();
    }

    public int approxTokens() {
        return Math.max(1, content.length() / 4);
    }
}
