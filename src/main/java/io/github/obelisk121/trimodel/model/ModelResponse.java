package io.github.obelisk121.trimodel.model;

/**
 * Unified model generation response returned by Claude, ChatGPT, or Gemini.
 */
public record ModelResponse(
        ModelProviderType provider,
        String modelId,
        String content,
        String thinking,
        long inputTokens,
        long outputTokens,
        long latencyMs,
        boolean success,
        String errorMessage
) {
    public ModelResponse {
        modelId = modelId == null ? "" : modelId;
        content = content == null ? "" : content;
        thinking = thinking == null ? "" : thinking;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static ModelResponse ok(
            ModelProviderType provider,
            String modelId,
            String content,
            String thinking,
            long inputTokens,
            long outputTokens,
            long latencyMs
    ) {
        return new ModelResponse(provider, modelId, content, thinking, inputTokens, outputTokens, latencyMs, true, "");
    }

    public static ModelResponse error(
            ModelProviderType provider,
            String modelId,
            String errorMessage,
            long latencyMs
    ) {
        return new ModelResponse(provider, modelId, "", "", 0, 0, latencyMs, false, errorMessage);
    }

    public long totalTokens() {
        return Math.max(0, inputTokens) + Math.max(0, outputTokens);
    }
}
