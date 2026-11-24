package io.github.obelisk121.trimodel.settings;

import io.github.obelisk121.trimodel.model.ConnectionMode;
import io.github.obelisk121.trimodel.model.GeminiApiStyle;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.OpenAiApiStyle;

import java.util.Objects;

/**
 * Serializable non-secret configuration for a single model provider.
 * API keys are stored separately via JetBrains PasswordSafe (SecretStorageService).
 */
public class ProviderConfig {
    public boolean enabled = true;
    public ConnectionMode connectionMode = ConnectionMode.REST_API;
    public String modelId = "";
    public String baseUrl = "";
    public OpenAiApiStyle openAiApiStyle = OpenAiApiStyle.RESPONSES;
    public GeminiApiStyle geminiApiStyle = GeminiApiStyle.INTERACTIONS;
    public String anthropicVersion = "2026-10-21";
    public String cliExecutable = "";
    public String cliArgs = "";

    public ProviderConfig() {
    }

    public static ProviderConfig defaultsFor(ModelProviderType type) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.enabled = true;
        cfg.connectionMode = ConnectionMode.REST_API;
        cfg.modelId = type.getDefaultModel();
        cfg.baseUrl = type.getDefaultBaseUrl();
        cfg.openAiApiStyle = OpenAiApiStyle.RESPONSES;
        cfg.geminiApiStyle = GeminiApiStyle.INTERACTIONS;
        cfg.anthropicVersion = "2026-10-21";
        cfg.cliExecutable = type.getDefaultCliExecutable();
        cfg.cliArgs = type.getDefaultCliArgs();
        return cfg;
    }

    public ProviderConfig copy() {
        ProviderConfig copy = new ProviderConfig();
        copy.enabled = this.enabled;
        copy.connectionMode = this.connectionMode;
        copy.modelId = this.modelId;
        copy.baseUrl = this.baseUrl;
        copy.openAiApiStyle = this.openAiApiStyle;
        copy.geminiApiStyle = this.geminiApiStyle;
        copy.anthropicVersion = this.anthropicVersion;
        copy.cliExecutable = this.cliExecutable;
        copy.cliArgs = this.cliArgs;
        return copy;
    }

    public String effectiveModelId(ModelProviderType type) {
        return (modelId == null || modelId.isBlank()) ? type.getDefaultModel() : modelId.trim();
    }

    public String effectiveBaseUrl(ModelProviderType type) {
        String url = (baseUrl == null || baseUrl.isBlank()) ? type.getDefaultBaseUrl() : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public String effectiveAnthropicVersion() {
        return (anthropicVersion == null || anthropicVersion.isBlank()) ? "2026-10-21" : anthropicVersion.trim();
    }

    public String effectiveCliExecutable(ModelProviderType type) {
        return (cliExecutable == null || cliExecutable.isBlank()) ? type.getDefaultCliExecutable() : cliExecutable.trim();
    }

    public String effectiveCliArgs(ModelProviderType type) {
        return cliArgs == null ? type.getDefaultCliArgs() : cliArgs.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderConfig that)) return false;
        return enabled == that.enabled
                && connectionMode == that.connectionMode
                && Objects.equals(modelId, that.modelId)
                && Objects.equals(baseUrl, that.baseUrl)
                && openAiApiStyle == that.openAiApiStyle
                && geminiApiStyle == that.geminiApiStyle
                && Objects.equals(anthropicVersion, that.anthropicVersion)
                && Objects.equals(cliExecutable, that.cliExecutable)
                && Objects.equals(cliArgs, that.cliArgs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, connectionMode, modelId, baseUrl, openAiApiStyle, geminiApiStyle,
                anthropicVersion, cliExecutable, cliArgs);
    }
}
