package io.github.obelisk121.trimodel.model;

import java.util.List;

/**
 * Provider-agnostic model generation request.
 */
public record ModelRequest(
        String systemPrompt,
        String userPrompt,
        List<ChatMessage> history,
        int maxOutputTokens,
        int timeoutSeconds
) {
    public ModelRequest {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        userPrompt = userPrompt == null ? "" : userPrompt;
        history = history == null ? List.of() : List.copyOf(history);
        maxOutputTokens = maxOutputTokens <= 0 ? 8192 : maxOutputTokens;
        timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
    }

    public static ModelRequest simple(String systemPrompt, String userPrompt) {
        return new ModelRequest(systemPrompt, userPrompt, List.of(), 8192, 120);
    }
}
