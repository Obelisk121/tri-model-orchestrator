package io.github.obelisk121.trimodel.model;

import java.time.Instant;

/**
 * Represents a message in a multi-turn conversation history.
 */
public record ChatMessage(
        String role,
        String content,
        Instant timestamp
) {
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content == null ? "" : content, Instant.now());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content == null ? "" : content, Instant.now());
    }
}
