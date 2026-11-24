package io.github.obelisk121.trimodel.provider;

import io.github.obelisk121.trimodel.model.ConnectionMode;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.settings.ProviderConfig;
import io.github.obelisk121.trimodel.settings.SecretStorageService;
import io.github.obelisk121.trimodel.settings.TriModelSettings;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves configured ModelClient instances for Claude, ChatGPT, and Gemini.
 */
public final class ModelClientFactory {
    private ModelClientFactory() {
    }

    public static ModelClient createClient(ModelProviderType type, ProviderConfig config, String apiKey) {
        ProviderConfig effective = config == null ? ProviderConfig.defaultsFor(type) : config;
        if (effective.connectionMode == ConnectionMode.LOCAL_CLI) {
            return new CliModelClient(
                    type,
                    effective.effectiveCliExecutable(type),
                    effective.effectiveCliArgs(type),
                    "cli:" + effective.effectiveCliExecutable(type)
            );
        }
        String resolvedKey = (apiKey != null && !apiKey.isBlank()) ? apiKey.trim() : SecretStorageService.resolveApiKey(type);
        return switch (type) {
            case CLAUDE -> new ClaudeClient(
                    effective.effectiveBaseUrl(type),
                    resolvedKey,
                    effective.effectiveModelId(type),
                    effective.effectiveAnthropicVersion()
            );
            case CHATGPT -> new ChatGptClient(
                    effective.effectiveBaseUrl(type),
                    resolvedKey,
                    effective.effectiveModelId(type),
                    effective.openAiApiStyle
            );
            case GEMINI -> new GeminiClient(
                    effective.effectiveBaseUrl(type),
                    resolvedKey,
                    effective.effectiveModelId(type),
                    effective.geminiApiStyle
            );
        };
    }

    public static Map<ModelProviderType, ModelClient> createConfiguredClients(TriModelSettings settings) {
        Map<ModelProviderType, ModelClient> clients = new EnumMap<>(ModelProviderType.class);
        for (ModelProviderType type : ModelProviderType.values()) {
            ProviderConfig cfg = settings.getProviderConfig(type);
            if (!cfg.enabled) {
                continue;
            }
            if (isReady(type, cfg)) {
                clients.put(type, createClient(type, cfg, null));
            }
        }
        return clients;
    }

    public static boolean isReady(ModelProviderType type, ProviderConfig cfg) {
        if (cfg == null || !cfg.enabled) {
            return false;
        }
        if (cfg.connectionMode == ConnectionMode.LOCAL_CLI) {
            return !cfg.effectiveCliExecutable(type).isBlank();
        }
        return !SecretStorageService.resolveApiKey(type).isBlank();
    }

    public static String readinessLabel(ModelProviderType type, ProviderConfig cfg) {
        if (cfg == null || !cfg.enabled) {
            return "Disabled";
        }
        if (cfg.connectionMode == ConnectionMode.LOCAL_CLI) {
            return "CLI (" + cfg.effectiveCliExecutable(type) + ")";
        }
        String stored = SecretStorageService.getStoredApiKey(type);
        if (!stored.isBlank()) {
            return "API Ready";
        }
        if (SecretStorageService.hasEnvironmentKey(type)) {
            return "API Ready (env)";
        }
        return "API Key Missing";
    }
}
