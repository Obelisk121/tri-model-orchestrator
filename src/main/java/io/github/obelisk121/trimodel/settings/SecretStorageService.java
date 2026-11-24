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
 * Secure storage for API keys backed by JetBrains PasswordSafe with an EDT-safe memory cache
 * and environment variable fallback (ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY).
 */
public final class SecretStorageService {
    private static final String SUBSYSTEM = "TriModelAiOrchestrator";
    private static final Map<ModelProviderType, String> CACHE = new ConcurrentHashMap<>();
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
                readDirectlyFromSafe(type);
            }
            CACHE_LOADED.set(true);
            if (onLoaded != null) {
                onLoaded.run();
            }
        });
    }

    public static String getStoredApiKey(ModelProviderType provider) {
        if (CACHE.containsKey(provider)) {
            return CACHE.getOrDefault(provider, "");
        }
        if (ApplicationManager.getApplication() == null) {
            return "";
        }
        // Avoid synchronous keychain lookup on EDT if cache hasn't finished warming yet
        if (ApplicationManager.getApplication().isDispatchThread() && !CACHE_LOADED.get()) {
            return "";
        }
        return readDirectlyFromSafe(provider);
    }

    /**
     * Loads the stored key directly from PasswordSafe (for Settings reset or background worker threads).
     */
    public static String loadStoredApiKeyBlocking(ModelProviderType provider) {
        if (ApplicationManager.getApplication() == null) {
            return CACHE.getOrDefault(provider, "");
        }
        return readDirectlyFromSafe(provider);
    }

    private static String readDirectlyFromSafe(ModelProviderType provider) {
        try {
            String pwd = PasswordSafe.getInstance().getPassword(attributesFor(provider));
            String normalized = pwd == null ? "" : pwd.trim();
            CACHE.put(provider, normalized);
            return normalized;
        } catch (Exception ignored) {
            return CACHE.getOrDefault(provider, "");
        }
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

    public static void saveApiKey(ModelProviderType provider, String apiKey) {
        String normalized = apiKey == null ? "" : apiKey.trim();
        CACHE.put(provider, normalized);
        CACHE_LOADED.set(true);
        if (ApplicationManager.getApplication() == null) {
            return;
        }
        CredentialAttributes attrs = attributesFor(provider);
        Credentials credentials = normalized.isEmpty() ? null : new Credentials(provider.name(), normalized);
        ApplicationManager.getApplication().executeOnPooledThread(() ->
                PasswordSafe.getInstance().set(attrs, credentials));
    }

    private static CredentialAttributes attributesFor(ModelProviderType provider) {
        String serviceName = CredentialAttributesKt.generateServiceName(SUBSYSTEM, provider.name());
        return new CredentialAttributes(serviceName, provider.name());
    }
}
