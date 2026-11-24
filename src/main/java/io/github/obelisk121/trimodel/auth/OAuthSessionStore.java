package io.github.obelisk121.trimodel.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.settings.SecretStorageService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages temporary (RAM-only) and optionally PasswordSafe-persisted OAuth 2.0 sessions.
 */
public final class OAuthSessionStore {
    private static final Gson GSON = new Gson();
    private static final Map<ModelProviderType, OAuthToken> MEMORY_TOKENS = new ConcurrentHashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private OAuthSessionStore() {
    }

    public static void addChangeListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeChangeListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    public static OAuthToken getToken(ModelProviderType provider) {
        OAuthToken inMem = MEMORY_TOKENS.get(provider);
        if (inMem != null && inMem.hasAccessToken()) {
            return inMem;
        }
        String persistedJson = SecretStorageService.getPersistedOAuthTokenJson(provider);
        if (persistedJson != null && !persistedJson.isBlank()) {
            OAuthToken parsed = deserialize(persistedJson);
            if (parsed != null && parsed.hasAccessToken()) {
                MEMORY_TOKENS.put(provider, parsed);
                return parsed;
            }
        }
        return null;
    }

    public static void saveToken(ModelProviderType provider, OAuthToken token) {
        if (token == null || !token.hasAccessToken()) {
            clearSession(provider);
            return;
        }
        MEMORY_TOKENS.put(provider, token);
        if (token.temporaryOnly()) {
            // Ensure no stale persisted token remains in PasswordSafe when user chooses temporary RAM-only mode
            SecretStorageService.savePersistedOAuthTokenJson(provider, "");
        } else {
            SecretStorageService.savePersistedOAuthTokenJson(provider, serialize(token));
        }
        notifyChanged();
    }

    public static void clearSession(ModelProviderType provider) {
        MEMORY_TOKENS.remove(provider);
        SecretStorageService.savePersistedOAuthTokenJson(provider, "");
        notifyChanged();
    }

    public static void clearAllSessions() {
        MEMORY_TOKENS.clear();
        for (ModelProviderType type : ModelProviderType.values()) {
            SecretStorageService.savePersistedOAuthTokenJson(type, "");
        }
        notifyChanged();
    }

    public static boolean hasUsableSession(ModelProviderType provider) {
        OAuthToken token = getToken(provider);
        return token != null && token.hasAccessToken() && (!token.isExpired() || token.canRefresh());
    }

    private static void notifyChanged() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (Exception ignored) {
            }
        }
    }

    private static String serialize(OAuthToken token) {
        JsonObject obj = new JsonObject();
        obj.addProperty("access_token", token.accessToken());
        obj.addProperty("refresh_token", token.refreshToken());
        obj.addProperty("token_type", token.tokenType());
        obj.addProperty("scope", token.scope());
        obj.addProperty("issued_at", token.issuedAtEpochSec());
        obj.addProperty("expires_at", token.expiresAtEpochSec());
        obj.addProperty("temporary_only", token.temporaryOnly());
        return GSON.toJson(obj);
    }

    private static OAuthToken deserialize(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String access = obj.has("access_token") ? obj.get("access_token").getAsString() : "";
            String refresh = obj.has("refresh_token") ? obj.get("refresh_token").getAsString() : "";
            String type = obj.has("token_type") ? obj.get("token_type").getAsString() : "Bearer";
            String scope = obj.has("scope") ? obj.get("scope").getAsString() : "";
            long issued = obj.has("issued_at") ? obj.get("issued_at").getAsLong() : 0L;
            long expires = obj.has("expires_at") ? obj.get("expires_at").getAsLong() : 0L;
            boolean tempOnly = obj.has("temporary_only") && obj.get("temporary_only").getAsBoolean();
            return new OAuthToken(access, refresh, type, scope, issued, expires, tempOnly);
        } catch (Exception ignored) {
            return null;
        }
    }
}
