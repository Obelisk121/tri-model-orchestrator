package io.github.obelisk121.trimodel.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * Represents an OAuth 2.0 session token acquired via browser PKCE authorization.
 */
public record OAuthToken(
        String accessToken,
        String refreshToken,
        String tokenType,
        String scope,
        long issuedAtEpochSec,
        long expiresAtEpochSec,
        boolean temporaryOnly
) {
    private static final long EXPIRY_SAFETY_MARGIN_SEC = 60L;

    public OAuthToken {
        accessToken = accessToken == null ? "" : accessToken.trim();
        refreshToken = refreshToken == null ? "" : refreshToken.trim();
        tokenType = (tokenType == null || tokenType.isBlank()) ? "Bearer" : tokenType.trim();
        scope = scope == null ? "" : scope.trim();
    }

    public boolean hasAccessToken() {
        return !accessToken.isBlank();
    }

    public boolean canRefresh() {
        return !refreshToken.isBlank();
    }

    public boolean isExpired() {
        if (expiresAtEpochSec <= 0) {
            return false; // Provider omitted expires_in
        }
        return Instant.now().getEpochSecond() + EXPIRY_SAFETY_MARGIN_SEC >= expiresAtEpochSec;
    }

    public String statusSummary() {
        if (!hasAccessToken()) {
            return "Not authorized";
        }
        String storageNote = temporaryOnly ? "temporary RAM session" : "saved in PasswordSafe";
        if (expiresAtEpochSec <= 0) {
            return "Authorized (" + storageNote + ")";
        }
        long remainingSec = expiresAtEpochSec - Instant.now().getEpochSecond();
        if (remainingSec <= 0) {
            return canRefresh() ? "Expired (will auto-refresh)" : "Expired — authorize again";
        }
        Duration d = Duration.ofSeconds(remainingSec);
        long hours = d.toHours();
        long mins = d.toMinutesPart();
        String timeStr = hours > 0 ? (hours + "h " + mins + "m left") : (Math.max(1, mins) + "m left");
        return "Authorized (" + timeStr + ", " + storageNote + ")";
    }
}
