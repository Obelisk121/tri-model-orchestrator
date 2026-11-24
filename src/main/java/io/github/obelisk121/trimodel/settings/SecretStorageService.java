package io.github.obelisk121.trimodel.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import io.github.obelisk121.trimodel.model.ModelProviderType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Secure storage for API keys, optional OAuth client secrets, and optionally persisted OAuth tokens,
 * backed by JetBrains PasswordSafe with an EDT-safe memory cache and environment variable fallback.
 */
public final class SecretStorageService {
    private static final String SUBSYSTEM = "TriModelAiOrchestrator";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final AtomicBoolean CACHE_LOADED = new AtomicBoolean(false);

    private SecretStorageService() {
    }

    /**
     * Loads credentials from PasswordSafe on a pooled thread so UI badges never block the EDT.
     */
    public static void warmCacheAsync(Runnable onLoaded) {
        if (ApplicationManager.getApplication() == null) {
            CACHE_LOADED.set(true);
            if (onLoaded != null) onLoaded.run();
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            for (ModelProviderType type : ModelProviderType.values()) {
                readKeyFromSafe(apiKeySlot(type));
                readKeyFromSafe(oauthSecretSlot(type));
                readKeyFromSafe(oauthTokenSlot(type));
            }
            CACHE_LOADED.set(true);
            if (onLoaded != null) {
                onLoaded.run();
            }
        });
    }

    // ---- API Keys ----

    public static String getStoredApiKey(ModelProviderType provider) {
        return getSlot(apiKeySlot(provider));
    }

    public static void saveApiKey(ModelProviderType provider, String apiKey) {
        saveSlot(apiKeySlot(provider), apiKey);
    }

    /**
     * Resolves an API key from PasswordSafe first, then falls back to standard environment variables.
     */
    public static String resolveApiKey(ModelProviderType provider) {
        String stored = getStoredApiKey(provider);
        if (!stored.isBlank()) {
            return stored;
        }
        String envVal = System.getenv(provider.getEnvVarName());
        if (envVal != null && !envVal.isBlank()) {
            return envVal.trim();
        }
        if (provider == ModelProviderType.GEMINI) {
            String googleKey = System.getenv("GOOGLE_API_KEY");
            if (googleKey != null && !googleKey.isBlank()) {
                return googleKey.trim();
            }
        }
        return "";
    }

    public static boolean hasEnvironmentKey(ModelProviderType provider) {
        String envVal = System.getenv(provider.getEnvVarName());
        if (envVal != null && !envVal.isBlank()) {
            return true;
        }
        if (provider == ModelProviderType.GEMINI) {
            String googleKey = System.getenv("GOOGLE_API_KEY");
            return googleKey != null && !googleKey.isBlank();
        }
        return false;
    }

    // ---- Optional OAuth Client Secret (e.g., Google Desktop OAuth apps) ----

    public static String getOAuthClientSecret(ModelProviderType provider) {
        return getSlot(oauthSecretSlot(provider));
    }

    public static void saveOAuthClientSecret(ModelProviderType provider, String clientSecret) {
        saveSlot(oauthSecretSlot(provider), clientSecret);
    }

    // ---- Optional Persistent OAuth Token (only written when temporaryOnly == false) ----

    public static String getPersistedOAuthTokenJson(ModelProviderType provider) {
        return getSlot(oauthTokenSlot(provider));
    }

    public static void savePersistedOAuthTokenJson(ModelProviderType provider, String tokenJson) {
        saveSlot(oauthTokenSlot(provider), tokenJson);
    }

    // ---- Shared PasswordSafe Slot Helpers ----

    private static String getSlot(String slot) {
        if (CACHE.containsKey(slot)) {
            return CACHE.getOrDefault(slot, "");
        }
        if (ApplicationManager.getApplication() == null) {
            return "";
        }
        if (ApplicationManager.getApplication().isDispatchThread() && !CACHE_LOADED.get()) {
            return "";
        }
        return readKeyFromSafe(slot);
    }

    private static String readKeyFromSafe(String slot) {
        try {
            String pwd = PasswordSafe.getInstance().getPassword(attributesFor(slot));
            String normalized = pwd == null ? "" : pwd.trim();
            CACHE.put(slot, normalized);
            return normalized;
        } catch (Exception ignored) {
            return CACHE.getOrDefault(slot, "");
        }
    }

    private static void saveSlot(String slot, String value) {
        String normalized = value == null ? "" : value.trim();
        CACHE.put(slot, normalized);
        CACHE_LOADED.set(true);
        if (ApplicationManager.getApplication() == null) {
            return;
        }
        CredentialAttributes attrs = attributesFor(slot);
        Credentials credentials = normalized.isEmpty() ? null : new Credentials(slot, normalized);
        ApplicationManager.getApplication().executeOnPooledThread(() ->
                PasswordSafe.getInstance().set(attrs, credentials));
    }

    private static String apiKeySlot(ModelProviderType provider) {
        return provider.name();
    }

    private static String oauthSecretSlot(ModelProviderType provider) {
        return provider.name() + "_OAUTH_CLIENT_SECRET";
    }

    private static String oauthTokenSlot(ModelProviderType provider) {
        return provider.name() + "_OAUTH_TOKEN";
    }

    private static CredentialAttributes attributesFor(String slot) {
        String serviceName = CredentialAttributesKt.generateServiceName(SUBSYSTEM, slot);
        return new CredentialAttributes(serviceName, slot);
    }
}
