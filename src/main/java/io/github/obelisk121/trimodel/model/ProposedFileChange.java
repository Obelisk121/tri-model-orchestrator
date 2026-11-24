package io.github.obelisk121.trimodel.model;

/**
 * Represents a code block or target file edit parsed from model output.
 */
public record ProposedFileChange(
        String filePath,
        String language,
        String code,
        boolean hasExplicitPath,
        String sourceLabel
) {
    public ProposedFileChange {
        filePath = filePath == null ? "" : filePath;
        language = language == null ? "" : language;
        code = code == null ? "" : code;
        sourceLabel = sourceLabel == null ? "" : sourceLabel;
    }

    public String displayName() {
        if (hasExplicitPath && !filePath.isBlank()) {
            return filePath;
        }
        String ext = language.isBlank() ? "snippet" : language;
        return "(Active Editor / " + ext + ")";
    }
}
