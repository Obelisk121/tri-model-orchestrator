package io.github.obelisk121.trimodel.provider;

import io.github.obelisk121.trimodel.auth.OAuthPkceService;
import io.github.obelisk121.trimodel.auth.OAuthSessionStore;
import io.github.obelisk121.trimodel.auth.OAuthToken;
import io.github.obelisk121.trimodel.model.ConnectionMode;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.settings.ProviderConfig;
import io.github.obelisk121.trimodel.settings.SecretStorageService;
import io.github.obelisk121.trimodel.settings.TriModelSettings;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves configured ModelClient instances for Claude, ChatGPT, and Gemini
 * across API Key, Temporary Browser OAuth (PKCE), and Local CLI modes.
 */
public final class ModelClientFactory {
    private ModelClientFactory() {
    }

    public static ModelClient createClient(ModelProviderType type, ProviderConfig config, String apiKeyOverride) {
        ProviderConfig effective = config == null ? ProviderConfig.defaultsFor(type) : config;

        if (effective.connectionMode == ConnectionMode.LOCAL_CLI) {
            return new CliModelClient(
                    type,
                    effective.effectiveCliExecutable(type),
                    effective.effectiveCliArgs(type),
                    "cli:" + effective.effectiveCliExecutable(type)
            );
        }

        if (effective.connectionMode == ConnectionMode.OAUTH_BROWSER) {
            String bearerToken = resolveUsableOAuthAccessToken(type, effective);
            return buildRestClient(type, effective, bearerToken, true);
        }

        String resolvedKey = (apiKeyOverride != null && !apiKeyOverride.isBlank())
                ? apiKeyOverride.trim()
                : SecretStorageService.resolveApiKey(type);
        return buildRestClient(type, effective, resolvedKey, false);
    }

    private static ModelClient buildRestClient(
            ModelProviderType type,
            ProviderConfig effective,
            String credential,
            boolean useBearerAuth
    ) {
        return switch (type) {
            case CLAUDE -> new ClaudeClient(
                    effective.effectiveBaseUrl(type),
                    credential,
                    effective.effectiveModelId(type),
                    effective.effectiveAnthropicVersion(),
                    useBearerAuth,
                    HttpTransport::postJson
            );
            case CHATGPT -> new ChatGptClient(
                    effective.effectiveBaseUrl(type),
                    credential,
                    effective.effectiveModelId(type),
                    effective.openAiApiStyle,
                    useBearerAuth,
                    HttpTransport::postJson
            );
            case GEMINI -> new GeminiClient(
                    effective.effectiveBaseUrl(type),
                    credential,
                    effective.effectiveModelId(type),
                    effective.geminiApiStyle,
                    useBearerAuth,
                    HttpTransport::postJson
            );
        };
    }

    private static String resolveUsableOAuthAccessToken(ModelProviderType type, ProviderConfig cfg) {
        OAuthToken token = OAuthSessionStore.getToken(type);
        if (token == null || !token.hasAccessToken()) {
            return "";
        }
        if (token.isExpired()) {
            if (token.canRefresh()) {
                String secret = SecretStorageService.getOAuthClientSecret(type);
                OAuthPkceService.AuthResult refreshed = new OAuthPkceService()
                        .refreshAccessToken(type, cfg, secret, token, () -> false);
                if (refreshed.success() && refreshed.token() != null) {
                    return refreshed.token().accessToken();
                }
            }
            return "";
        }
        return token.accessToken();
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
        return switch (cfg.connectionMode == null ? ConnectionMode.REST_API : cfg.connectionMode) {
            case LOCAL_CLI -> !cfg.effectiveCliExecutable(type).isBlank();
            case OAUTH_BROWSER -> OAuthSessionStore.hasUsableSession(type);
            case REST_API -> !SecretStorageService.resolveApiKey(type).isBlank();
        };
    }

    public static String readinessLabel(ModelProviderType type, ProviderConfig cfg) {
        if (cfg == null || !cfg.enabled) {
            return "Disabled";
        }
        ConnectionMode mode = cfg.connectionMode == null ? ConnectionMode.REST_API : cfg.connectionMode;
        if (mode == ConnectionMode.LOCAL_CLI) {
            return "CLI (" + cfg.effectiveCliExecutable(type) + ")";
        }
        if (mode == ConnectionMode.OAUTH_BROWSER) {
            OAuthToken token = OAuthSessionStore.getToken(type);
            if (token == null || !token.hasAccessToken()) {
                return "OAuth Sign-In Needed";
            }
            return token.statusSummary();
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
