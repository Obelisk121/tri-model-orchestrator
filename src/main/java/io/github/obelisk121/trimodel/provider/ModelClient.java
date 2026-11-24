package io.github.obelisk121.trimodel.provider;

import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.ModelRequest;
import io.github.obelisk121.trimodel.model.ModelResponse;

import java.util.function.BooleanSupplier;

/**
 * Common execution contract for Claude, ChatGPT, and Gemini clients (REST API or local CLI).
 */
public interface ModelClient {
    ModelProviderType getProviderType();

    String getModelId();

    ModelResponse generate(ModelRequest request, BooleanSupplier isCancelled);
}
