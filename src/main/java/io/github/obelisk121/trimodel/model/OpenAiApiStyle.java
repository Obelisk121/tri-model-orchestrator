package io.github.obelisk121.trimodel.model;

public enum OpenAiApiStyle {
    RESPONSES("Responses API (/v1/responses)"),
    CHAT_COMPLETIONS("Chat Completions (/v1/chat/completions)");

    private final String displayName;

    OpenAiApiStyle(String displayName) {
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
