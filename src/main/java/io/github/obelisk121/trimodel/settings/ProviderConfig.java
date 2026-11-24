package io.github.obelisk121.trimodel.settings;

import io.github.obelisk121.trimodel.model.ConnectionMode;
import io.github.obelisk121.trimodel.model.GeminiApiStyle;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.OpenAiApiStyle;

import java.util.Objects;

/**
 * Serializable non-secret configuration for a single model provider.
 * API keys, optional OAuth client secrets, and persistent tokens are stored separately
 * via JetBrains PasswordSafe (SecretStorageService) or held strictly in RAM for temporary sessions.
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

    // Browser OAuth 2.0 (PKCE + Loopback) settings
    public String oauthClientId = "";
    public String oauthAuthorizeUrl = "";
    public String oauthTokenUrl = "";
    public String oauthScopes = "";
    public int oauthCallbackPort = 0; // 0 = random ephemeral loopback port
    public boolean oauthTemporaryOnly = true;

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
        cfg.oauthClientId = "";
        cfg.oauthAuthorizeUrl = type.getDefaultOAuthAuthorizeUrl();
        cfg.oauthTokenUrl = type.getDefaultOAuthTokenUrl();
        cfg.oauthScopes = type.getDefaultOAuthScopes();
        cfg.oauthCallbackPort = 0;
        cfg.oauthTemporaryOnly = true;
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
        copy.oauthClientId = this.oauthClientId;
        copy.oauthAuthorizeUrl = this.oauthAuthorizeUrl;
        copy.oauthTokenUrl = this.oauthTokenUrl;
        copy.oauthScopes = this.oauthScopes;
        copy.oauthCallbackPort = this.oauthCallbackPort;
        copy.oauthTemporaryOnly = this.oauthTemporaryOnly;
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

    public String effectiveOAuthClientId() {
        return oauthClientId == null ? "" : oauthClientId.trim();
    }

    public String effectiveOAuthAuthorizeUrl(ModelProviderType type) {
        return (oauthAuthorizeUrl == null || oauthAuthorizeUrl.isBlank())
                ? type.getDefaultOAuthAuthorizeUrl() : oauthAuthorizeUrl.trim();
    }

    public String effectiveOAuthTokenUrl(ModelProviderType type) {
        return (oauthTokenUrl == null || oauthTokenUrl.isBlank())
                ? type.getDefaultOAuthTokenUrl() : oauthTokenUrl.trim();
    }

    public String effectiveOAuthScopes(ModelProviderType type) {
        return (oauthScopes == null || oauthScopes.isBlank())
                ? type.getDefaultOAuthScopes() : oauthScopes.trim();
    }

    public int effectiveOAuthCallbackPort() {
        return (oauthCallbackPort < 0 || oauthCallbackPort > 65535) ? 0 : oauthCallbackPort;
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
                && Objects.equals(cliArgs, that.cliArgs)
                && Objects.equals(oauthClientId, that.oauthClientId)
                && Objects.equals(oauthAuthorizeUrl, that.oauthAuthorizeUrl)
                && Objects.equals(oauthTokenUrl, that.oauthTokenUrl)
                && Objects.equals(oauthScopes, that.oauthScopes)
                && oauthCallbackPort == that.oauthCallbackPort
                && oauthTemporaryOnly == that.oauthTemporaryOnly;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, connectionMode, modelId, baseUrl, openAiApiStyle, geminiApiStyle,
                anthropicVersion, cliExecutable, cliArgs, oauthClientId, oauthAuthorizeUrl, oauthTokenUrl,
                oauthScopes, oauthCallbackPort, oauthTemporaryOnly);
    }
}
