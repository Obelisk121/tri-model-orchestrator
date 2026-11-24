package io.github.obelisk121.trimodel.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.BrowserUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.provider.HttpTransport;
import io.github.obelisk121.trimodel.settings.ProviderConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * RFC 8252 + RFC 7636 OAuth 2.0 Authorization Code flow with PKCE (S256) and
 * ephemeral localhost (127.0.0.1) loopback callback for default web browser sign-in.
 */
public final class OAuthPkceService {

    public static final String CALLBACK_PATH = "/callback";
    private static final int AUTH_TIMEOUT_SECONDS = 180;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @FunctionalInterface
    public interface FormPostFunction {
        HttpTransport.HttpResult postForm(
                String url,
                Map<String, String> formParams,
                int timeoutSeconds,
                BooleanSupplier isCancelled
        );
    }

    public record CallbackOutcome(boolean success, String code, String errorMessage) {
        public static CallbackOutcome ok(String code) {
            return new CallbackOutcome(true, code, "");
        }

        public static CallbackOutcome error(String message) {
            return new CallbackOutcome(false, "", message);
        }
    }

    public record AuthResult(boolean success, OAuthToken token, String errorMessage) {
        public static AuthResult ok(OAuthToken token) {
            return new AuthResult(true, token, "");
        }

        public static AuthResult error(String message) {
            return new AuthResult(false, null, message);
        }
    }

    private final FormPostFunction formTransport;

    public OAuthPkceService() {
        this(OAuthPkceService::defaultPostForm);
    }

    public OAuthPkceService(FormPostFunction formTransport) {
        this.formTransport = formTransport == null ? OAuthPkceService::defaultPostForm : formTransport;
    }

    public static String generateCodeVerifier() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String deriveCodeChallengeS256(String codeVerifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String generateState() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String validateEndpointUrl(String url, String label) {
        if (url == null || url.isBlank()) {
            return label + " must not be empty.";
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return label + " must be a valid absolute URL.";
            }
            boolean isHttps = "https".equalsIgnoreCase(scheme);
            boolean isLoopbackHttp = "http".equalsIgnoreCase(scheme)
                    && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
            if (!isHttps && !isLoopbackHttp) {
                return label + " must use HTTPS (only localhost/127.0.0.1 allows HTTP for local testing).";
            }
            return null;
        } catch (Exception e) {
            return label + " is invalid: " + e.getMessage();
        }
    }

    public static String buildAuthorizationUrl(
            ModelProviderType provider,
            ProviderConfig config,
            String redirectUri,
            String state,
            String codeChallenge
    ) {
        String authorizeUrl = config.effectiveOAuthAuthorizeUrl(provider);
        String clientId = config.effectiveOAuthClientId();
        String scopes = config.effectiveOAuthScopes(provider);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        if (!scopes.isBlank()) {
            params.put("scope", scopes);
        }
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");

        if (provider == ModelProviderType.GEMINI) {
            params.put("access_type", config.oauthTemporaryOnly ? "online" : "offline");
            params.put("prompt", config.oauthTemporaryOnly ? "select_account" : "consent");
        }

        String separator = authorizeUrl.contains("?") ? "&" : "?";
        return authorizeUrl + separator + encodeForm(params);
    }

    /**
     * Launches the user's default web browser for official OAuth 2.0 PKCE authorization,
     * receives the loopback redirect on 127.0.0.1, exchanges the authorization code for an access token,
     * and stores the session in OAuthSessionStore.
     */
    public AuthResult authorizeInBrowser(
            ModelProviderType provider,
            ProviderConfig config,
            String clientSecret,
            Consumer<String> browserOpener,
            Consumer<String> statusCallback,
            BooleanSupplier isCancelled
    ) {
        BooleanSupplier cancel = isCancelled == null ? () -> false : isCancelled;
        Consumer<String> status = statusCallback == null ? s -> {} : statusCallback;
        Consumer<String> openBrowser = browserOpener == null ? BrowserUtil::browse : browserOpener;

        String clientId = config.effectiveOAuthClientId();
        if (clientId.isBlank()) {
            return AuthResult.error("OAuth Client ID is required for " + provider.getFullName()
                    + ". Enter your registered OAuth Client ID in Settings.");
        }
        String authUrlErr = validateEndpointUrl(config.effectiveOAuthAuthorizeUrl(provider), "OAuth Authorize URL");
        if (authUrlErr != null) {
            return AuthResult.error(authUrlErr);
        }
        String tokenUrlErr = validateEndpointUrl(config.effectiveOAuthTokenUrl(provider), "OAuth Token URL");
        if (tokenUrlErr != null) {
            return AuthResult.error(tokenUrlErr);
        }

        String codeVerifier = generateCodeVerifier();
        String codeChallenge = deriveCodeChallengeS256(codeVerifier);
        String expectedState = generateState();

        HttpServer server = null;
        try {
            int requestedPort = config.effectiveOAuthCallbackPort();
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
            int boundPort = server.getAddress().getPort();
            String redirectUri = "http://127.0.0.1:" + boundPort + CALLBACK_PATH;

            CompletableFuture<CallbackOutcome> callbackFuture = new CompletableFuture<>();
            server.createContext(CALLBACK_PATH, exchange ->
                    handleCallbackExchange(exchange, provider, expectedState, callbackFuture));
            server.start();

            String authUrl = buildAuthorizationUrl(provider, config, redirectUri, expectedState, codeChallenge);
            status.accept("Opening default browser for " + provider.getShortName() + " authorization...");
            openBrowser.accept(authUrl);
            status.accept("Waiting for browser authorization on 127.0.0.1:" + boundPort + " (up to 3 min)...");

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(AUTH_TIMEOUT_SECONDS);
            CallbackOutcome outcome = null;
            while (outcome == null) {
                if (cancel.getAsBoolean()) {
                    return AuthResult.error("Browser authorization cancelled.");
                }
                if (System.nanoTime() > deadlineNanos) {
                    return AuthResult.error("Timed out waiting for browser authorization after " + AUTH_TIMEOUT_SECONDS + "s.");
                }
                try {
                    outcome = callbackFuture.get(150, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                }
            }

            if (!outcome.success()) {
                return AuthResult.error(outcome.errorMessage());
            }

            status.accept("Exchanging authorization code for temporary session token...");
            return exchangeAuthorizationCode(provider, config, clientSecret, outcome.code(), codeVerifier, redirectUri, cancel);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AuthResult.error("Browser authorization interrupted.");
        } catch (Exception e) {
            return AuthResult.error("Failed to run browser OAuth flow: " + e.getMessage());
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    /**
     * Exchanges an authorization code + PKCE code_verifier at the provider's token endpoint.
     */
    public AuthResult exchangeAuthorizationCode(
            ModelProviderType provider,
            ProviderConfig config,
            String clientSecret,
            String authorizationCode,
            String codeVerifier,
            String redirectUri,
            BooleanSupplier isCancelled
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("client_id", config.effectiveOAuthClientId());
        if (clientSecret != null && !clientSecret.isBlank()) {
            params.put("client_secret", clientSecret.trim());
        }
        params.put("code", authorizationCode);
        params.put("code_verifier", codeVerifier);
        params.put("redirect_uri", redirectUri);

        return executeTokenRequest(provider, config.effectiveOAuthTokenUrl(provider), params,
                config.oauthTemporaryOnly, "", isCancelled);
    }

    /**
     * Refreshes an expired OAuth access token using its refresh token.
     */
    public AuthResult refreshAccessToken(
            ModelProviderType provider,
            ProviderConfig config,
            String clientSecret,
            OAuthToken existingToken,
            BooleanSupplier isCancelled
    ) {
        if (existingToken == null || !existingToken.canRefresh()) {
            return AuthResult.error("No refresh token available; please authorize in browser again.");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("client_id", config.effectiveOAuthClientId());
        if (clientSecret != null && !clientSecret.isBlank()) {
            params.put("client_secret", clientSecret.trim());
        }
        params.put("refresh_token", existingToken.refreshToken());

        return executeTokenRequest(provider, config.effectiveOAuthTokenUrl(provider), params,
                existingToken.temporaryOnly(), existingToken.refreshToken(), isCancelled);
    }

    private AuthResult executeTokenRequest(
            ModelProviderType provider,
            String tokenUrl,
            Map<String, String> params,
            boolean temporaryOnly,
            String fallbackRefreshToken,
            BooleanSupplier isCancelled
    ) {
        HttpTransport.HttpResult res = formTransport.postForm(tokenUrl, params, 30, isCancelled);
        if (res.cancelled()) {
            return AuthResult.error("Token exchange cancelled.");
        }
        if (res.transportError() != null) {
            return AuthResult.error(res.transportError());
        }
        if (!res.is2xx()) {
            return AuthResult.error("OAuth token endpoint error: "
                    + HttpTransport.extractErrorDetail(res.statusCode(), res.body()));
        }

        try {
            JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
            String accessToken = HttpTransport.getString(obj, "access_token");
            if (accessToken.isBlank()) {
                String errDesc = HttpTransport.getString(obj, "error_description");
                String err = HttpTransport.getString(obj, "error");
                String msg = !errDesc.isBlank() ? errDesc : (!err.isBlank() ? err : "Token response missing access_token.");
                return AuthResult.error("OAuth error: " + msg);
            }
            String refreshToken = HttpTransport.getString(obj, "refresh_token");
            if (refreshToken.isBlank() && !temporaryOnly) {
                refreshToken = fallbackRefreshToken == null ? "" : fallbackRefreshToken;
            }
            if (temporaryOnly) {
                // Keep temporary sessions strictly short-lived without retaining long-lived refresh tokens
                refreshToken = "";
            }
            String tokenType = HttpTransport.getString(obj, "token_type");
            String scope = HttpTransport.getString(obj, "scope");
            long expiresIn = HttpTransport.getLong(obj, "expires_in");
            long now = Instant.now().getEpochSecond();
            long expiresAt = expiresIn > 0 ? now + expiresIn : 0L;

            OAuthToken token = new OAuthToken(
                    accessToken,
                    refreshToken,
                    tokenType,
                    scope,
                    now,
                    expiresAt,
                    temporaryOnly
            );
            OAuthSessionStore.saveToken(provider, token);
            return AuthResult.ok(token);
        } catch (Exception e) {
            return AuthResult.error("Failed to parse OAuth token response: " + e.getMessage());
        }
    }

    /**
     * Parses and validates an OAuth redirect query string (CSRF state + authorization code).
     */
    public static CallbackOutcome processCallbackQuery(String rawQuery, String expectedState) {
        Map<String, String> query = parseQueryString(rawQuery);
        String error = query.getOrDefault("error", "");
        if (!error.isBlank()) {
            String desc = query.getOrDefault("error_description", "");
            return CallbackOutcome.error("Provider declined authorization (" + error + ")"
                    + (desc.isBlank() ? "" : ": " + desc));
        }

        String receivedState = query.getOrDefault("state", "");
        if (expectedState == null || expectedState.isBlank()
                || !MessageDigest.isEqual(
                expectedState.getBytes(StandardCharsets.UTF_8),
                receivedState.getBytes(StandardCharsets.UTF_8))) {
            return CallbackOutcome.error("OAuth CSRF state mismatch — authorization rejected for safety.");
        }

        String code = query.getOrDefault("code", "");
        if (code.isBlank()) {
            return CallbackOutcome.error("Authorization callback did not include a 'code' parameter.");
        }
        return CallbackOutcome.ok(code);
    }

    private static void handleCallbackExchange(
            HttpExchange exchange,
            ModelProviderType provider,
            String expectedState,
            CompletableFuture<CallbackOutcome> future
    ) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendHtml(exchange, 405, "Method Not Allowed", "Only GET callbacks are accepted.");
            return;
        }
        CallbackOutcome outcome = processCallbackQuery(exchange.getRequestURI().getRawQuery(), expectedState);
        if (outcome.success()) {
            sendHtml(exchange, 200,
                    provider.getShortName() + " Authorization Received",
                    "Authorization succeeded for <b>" + escape(provider.getFullName()) + "</b>. "
                            + "You may close this browser tab and return to PyCharm.");
        } else {
            sendHtml(exchange, 400,
                    provider.getShortName() + " Authorization Failed",
                    escape(outcome.errorMessage()) + " You may close this tab and return to PyCharm.");
        }
        future.complete(outcome);
    }

    private static void sendHtml(HttpExchange exchange, int status, String title, String messageHtml) throws IOException {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>" + escape(title) + "</title>"
                + "<style>body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#1e1f22;"
                + "color:#dfe1e5;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;}"
                + ".card{background:#2b2d30;border:1px solid #393b40;border-radius:10px;padding:24px 28px;max-width:460px;}"
                + "h2{margin:0 0 10px 0;font-size:18px;}p{margin:0;line-height:1.5;color:#bcbfc7;}</style></head>"
                + "<body><div class='card'><h2>" + escape(title) + "</h2><p>" + messageHtml + "</p></div></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static HttpTransport.HttpResult defaultPostForm(
            String url,
            Map<String, String> formParams,
            int timeoutSeconds,
            BooleanSupplier isCancelled
    ) {
        long startNanos = System.nanoTime();
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            return new HttpTransport.HttpResult(0, "", 0, true, "Cancelled");
        }
        try {
            String body = encodeForm(formParams);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            return new HttpTransport.HttpResult(response.statusCode(), response.body(), latencyMs, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            return new HttpTransport.HttpResult(0, "", latencyMs, true, "Interrupted");
        } catch (Exception e) {
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            return new HttpTransport.HttpResult(0, "", latencyMs, false,
                    "Network error calling OAuth token endpoint: " + e.getMessage());
        }
    }

    static String encodeForm(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static Map<String, String> parseQueryString(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) continue;
            int idx = pair.indexOf('=');
            String k = idx >= 0 ? pair.substring(0, idx) : pair;
            String v = idx >= 0 ? pair.substring(idx + 1) : "";
            out.put(
                    URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8)
            );
        }
        return out;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
