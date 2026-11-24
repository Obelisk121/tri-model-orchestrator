package io.github.obelisk121.trimodel.model;

public enum GeminiApiStyle {
    INTERACTIONS("Interactions API (/v1beta/interactions)"),
    GENERATE_CONTENT("GenerateContent (/v1beta/models/...:generateContent)");

    private final String displayName;

    GeminiApiStyle(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
