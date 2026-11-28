package io.github.obelisk121.trimodel.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.obelisk121.trimodel.auth.OAuthPkceService;
import io.github.obelisk121.trimodel.auth.OAuthSessionStore;
import io.github.obelisk121.trimodel.auth.OAuthToken;
import io.github.obelisk121.trimodel.model.ConnectionMode;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.settings.ProviderConfig;
import io.github.obelisk121.trimodel.settings.SecretStorageService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamically discovers available AI models from live provider REST APIs
 * (Anthropic GET /v1/models, OpenAI GET /v1/models, Google Gemini GET /v1beta/models)
 * and official local CLIs (gemini models -o json, agy models, codex debug models)
 * so model selection never relies on hardcoded static model lists.
 */
public final class ModelDiscoveryService {

    public static final String AUTO_MODEL_ID = "auto";

    private static final int MAX_API_PAGES = 5;
    private static final int HTTP_TIMEOUT_SECONDS = 15;
    private static final int CLI_TIMEOUT_SECONDS = 20;

    private static final Pattern AGY_MODEL_LINE = Pattern.compile("^\\s*([A-Za-z0-9][A-Za-z0-9._:/-]*)(?:\\t+|\\s{2,})(.+?)\\s*$");
    private static final Pattern GEMINI_TEXT_MODEL_LINE = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._:/-]*)\\s+\\(([^)]+)\\)\\s*$");
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("(\\d+)(?:\\.(\\d+))?");

    private static final Map<String, DiscoveryResult> CACHE = new ConcurrentHashMap<>();

    public record DiscoveredModel(String id, String displayName) {
        public DiscoveredModel {
            id = id == null ? "" : id.trim();
            displayName = (displayName == null || displayName.isBlank()) ? id : displayName.trim();
        }

        public static DiscoveredModel auto(String label) {
            return new DiscoveredModel(AUTO_MODEL_ID, label == null || label.isBlank() ? "Auto (CLI default)" : label);
        }

        public boolean isAuto() {
            return AUTO_MODEL_ID.equalsIgnoreCase(id) || id.isBlank();
        }

        public String comboLabel() {
            if (isAuto()) {
                return displayName;
            }
            if (!displayName.equalsIgnoreCase(id) && !displayName.isBlank()) {
                return id + " — " + displayName;
            }
            return id;
        }
    }

    public record DiscoveryResult(
            boolean success,
            List<DiscoveredModel> models,
            String source,
            String errorMessage
    ) {
        public DiscoveryResult {
            models = models == null ? List.of() : List.copyOf(models);
            source = source == null ? "" : source;
            errorMessage = errorMessage == null ? "" : errorMessage;
        }

        public static DiscoveryResult ok(List<DiscoveredModel> models, String source) {
            return new DiscoveryResult(true, models, source, "");
        }

        public static DiscoveryResult error(String errorMessage) {
            return new DiscoveryResult(false, List.of(), "", errorMessage == null ? "Model discovery failed." : errorMessage);
        }

        public List<String> modelIds() {
            List<String> ids = new ArrayList<>(models.size());
            for (DiscoveredModel m : models) {
                if (!m.id().isBlank()) {
                    ids.add(m.id());
                }
            }
            return ids;
        }

        public boolean containsModelId(String modelId) {
            if (modelId == null || modelId.isBlank()) {
                return false;
            }
            String trimmed = modelId.trim();
            for (DiscoveredModel m : models) {
                if (m.id().equals(trimmed)) {
                    return true;
                }
            }
            return false;
        }

        public String firstConcreteModelId() {
            for (DiscoveredModel m : models) {
                if (!m.isAuto() && !m.id().isBlank()) {
                    return m.id();
                }
            }
            return models.isEmpty() ? "" : models.get(0).id();
        }
    }

    @FunctionalInterface
    public interface CliRunner {
        CliModelClient.CliCommandResult run(String executable, List<String> args, int timeoutSeconds, BooleanSupplier isCancelled);
    }

    private final HttpTransport.GetFunction httpGet;
    private final CliRunner cliRunner;

    public ModelDiscoveryService() {
        this(HttpTransport::getJson, ModelDiscoveryService::defaultCliRunner);
    }

    public ModelDiscoveryService(HttpTransport.GetFunction httpGet, CliRunner cliRunner) {
        this.httpGet = httpGet == null ? HttpTransport::getJson : httpGet;
        this.cliRunner = cliRunner == null ? ModelDiscoveryService::defaultCliRunner : cliRunner;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static void invalidateCache(ModelProviderType type) {
        if (type == null) return;
        String prefix = type.name() + "|";
        CACHE.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public DiscoveryResult fetchAvailableModels(
            ModelProviderType type,
            ProviderConfig config,
            String apiKeyOverride,
            boolean forceRefresh,
            BooleanSupplier isCancelled
    ) {
        ProviderConfig effective = config == null ? ProviderConfig.defaultsFor(type) : config;
        ConnectionMode mode = effective.connectionMode == null ? ConnectionMode.REST_API : effective.connectionMode;
        BooleanSupplier cancel = isCancelled == null ? () -> false : isCancelled;

        if (mode == ConnectionMode.LOCAL_CLI) {
            String exe = effective.effectiveCliExecutable(type);
            String key = cacheKey(type, mode, exe, "");
            if (!forceRefresh) {
                DiscoveryResult cached = CACHE.get(key);
                if (cached != null && cached.success() && !cached.models().isEmpty()) {
                    return cached;
                }
            }
            DiscoveryResult fresh = discoverFromCli(type, effective, apiKeyOverride, cancel);
            if (fresh.success() && !fresh.models().isEmpty()) {
                CACHE.put(key, fresh);
            }
            return fresh;
        }

        boolean useBearer = (mode == ConnectionMode.OAUTH_BROWSER);
        String credential = resolveApiCredential(type, effective, apiKeyOverride, useBearer, cancel);
        if (credential.isBlank()) {
            return DiscoveryResult.error(useBearer
                    ? "Sign in with OAuth first to load available " + type.getShortName() + " models."
                    : "Enter a " + type.getShortName() + " API key (or set " + type.getEnvVarName() + ") to fetch available models.");
        }

        String baseUrl = effective.effectiveBaseUrl(type);
        String key = cacheKey(type, mode, baseUrl, sha256Short(credential));
        if (!forceRefresh) {
            DiscoveryResult cached = CACHE.get(key);
            if (cached != null && cached.success() && !cached.models().isEmpty()) {
                return cached;
            }
        }

        DiscoveryResult fresh = discoverFromRestApi(type, effective, credential, useBearer, cancel);
        if (fresh.success() && !fresh.models().isEmpty()) {
            CACHE.put(key, fresh);
        }
        return fresh;
    }

    /**
     * Resolves an available model ID for REST/OAuth execution when the saved config has no model selected yet.
     */
    public String resolveAvailableModelForRest(
            ModelProviderType type,
            ProviderConfig config,
            String apiKeyOverride,
            BooleanSupplier isCancelled
    ) {
        ProviderConfig effective = config == null ? ProviderConfig.defaultsFor(type) : config;
        String configured = effective.effectiveModelId(type);
        if (!configured.isBlank() && !AUTO_MODEL_ID.equalsIgnoreCase(configured)) {
            return configured;
        }
        DiscoveryResult res = fetchAvailableModels(type, effective, apiKeyOverride, false, isCancelled);
        if (res.success()) {
            return res.firstConcreteModelId();
        }
        return "";
    }

    public DiscoveryResult discoverFromRestApi(
            ModelProviderType type,
            ProviderConfig config,
            String credential,
            boolean useBearerAuth,
            BooleanSupplier isCancelled
    ) {
        return switch (type) {
            case CLAUDE -> fetchAnthropicModels(
                    config.effectiveBaseUrl(type), credential, config.effectiveAnthropicVersion(), useBearerAuth, isCancelled);
            case CHATGPT -> fetchOpenAiModels(
                    config.effectiveBaseUrl(type), credential, isCancelled);
            case GEMINI -> fetchGeminiModels(
                    config.effectiveBaseUrl(type), credential, useBearerAuth, isCancelled);
        };
    }

    private DiscoveryResult fetchAnthropicModels(
            String baseUrl,
            String credential,
            String anthropicVersion,
            boolean useBearerAuth,
            BooleanSupplier isCancelled
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (useBearerAuth) {
            headers.put("Authorization", "Bearer " + credential);
        } else {
            headers.put("x-api-key", credential);
        }
        headers.put("anthropic-version", anthropicVersion);

        Map<String, DiscoveredModel> collected = new LinkedHashMap<>();
        String afterId = null;

        for (int page = 0; page < MAX_API_PAGES; page++) {
            String url = buildAnthropicModelsUrl(baseUrl, afterId);
            HttpTransport.HttpResult res = httpGet.get(url, headers, HTTP_TIMEOUT_SECONDS, isCancelled);
            if (res.cancelled()) return DiscoveryResult.error("Cancelled.");
            if (res.transportError() != null) return DiscoveryResult.error(res.transportError());
            if (!res.is2xx()) {
                return DiscoveryResult.error(HttpTransport.extractErrorDetail(res.statusCode(), res.body()));
            }
            AnthropicModelsPage parsed = parseAnthropicModelsPage(res.body());
            for (DiscoveredModel m : parsed.models()) {
                collected.putIfAbsent(m.id(), m);
            }
            if (!parsed.hasMore() || parsed.lastId().isBlank() || parsed.lastId().equals(afterId)) {
                break;
            }
            afterId = parsed.lastId();
        }

        if (collected.isEmpty()) {
            return DiscoveryResult.error("Anthropic /v1/models returned no available models for this credential.");
        }
        return DiscoveryResult.ok(new ArrayList<>(collected.values()), "Anthropic API (/v1/models)");
    }

    private DiscoveryResult fetchOpenAiModels(
            String baseUrl,
            String credential,
            BooleanSupplier isCancelled
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + credential);

        String url = buildOpenAiModelsUrl(baseUrl);
        HttpTransport.HttpResult res = httpGet.get(url, headers, HTTP_TIMEOUT_SECONDS, isCancelled);
        if (res.cancelled()) return DiscoveryResult.error("Cancelled.");
        if (res.transportError() != null) return DiscoveryResult.error(res.transportError());
        if (!res.is2xx()) {
            return DiscoveryResult.error(HttpTransport.extractErrorDetail(res.statusCode(), res.body()));
        }

        List<DiscoveredModel> models = parseOpenAiModelsJson(res.body());
        if (models.isEmpty()) {
            return DiscoveryResult.error("OpenAI /v1/models returned no available models for this credential.");
        }
        return DiscoveryResult.ok(models, "OpenAI API (/v1/models)");
    }

    private DiscoveryResult fetchGeminiModels(
            String baseUrl,
            String credential,
            boolean useBearerAuth,
            BooleanSupplier isCancelled
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (useBearerAuth) {
            headers.put("Authorization", "Bearer " + credential);
        } else {
            headers.put("x-goog-api-key", credential);
        }

        Map<String, DiscoveredModel> collected = new LinkedHashMap<>();
        String pageToken = null;

        for (int page = 0; page < MAX_API_PAGES; page++) {
            String url = buildGeminiModelsUrl(baseUrl, pageToken);
            HttpTransport.HttpResult res = httpGet.get(url, headers, HTTP_TIMEOUT_SECONDS, isCancelled);
            if (res.cancelled()) return DiscoveryResult.error("Cancelled.");
            if (res.transportError() != null) return DiscoveryResult.error(res.transportError());
            if (!res.is2xx()) {
                return DiscoveryResult.error(HttpTransport.extractErrorDetail(res.statusCode(), res.body()));
            }
            GeminiModelsPage parsed = parseGeminiModelsPage(res.body());
            for (DiscoveredModel m : parsed.models()) {
                collected.putIfAbsent(m.id(), m);
            }
            if (parsed.nextPageToken().isBlank() || parsed.nextPageToken().equals(pageToken)) {
                break;
            }
            pageToken = parsed.nextPageToken();
        }

        List<DiscoveredModel> sorted = sortGeminiModels(new ArrayList<>(collected.values()));
        if (sorted.isEmpty()) {
            return DiscoveryResult.error("Gemini /models API returned no text-generation models for this credential.");
        }
        return DiscoveryResult.ok(sorted, "Gemini API (/models)");
    }

    private DiscoveryResult discoverFromCli(
            ModelProviderType type,
            ProviderConfig config,
            String apiKeyOverride,
            BooleanSupplier isCancelled
    ) {
        String exe = config.effectiveCliExecutable(type);
        String canonical = CliModelClient.canonicalCliName(exe);

        if ("gemini".equals(canonical)) {
            CliModelClient.CliCommandResult jsonRes = cliRunner.run(exe, List.of("models", "-o", "json"), CLI_TIMEOUT_SECONDS, isCancelled);
            if (jsonRes.launchError() == null && jsonRes.exitCode() == 0) {
                List<DiscoveredModel> models = parseGeminiCliModelsJson(jsonRes.stdout());
                if (!models.isEmpty()) {
                    return DiscoveryResult.ok(models, "gemini CLI");
                }
            }
            CliModelClient.CliCommandResult textRes = cliRunner.run(exe, List.of("--list-models"), CLI_TIMEOUT_SECONDS, isCancelled);
            if (textRes.launchError() == null && textRes.exitCode() == 0) {
                List<DiscoveredModel> models = parseGeminiCliModelsText(textRes.stdout());
                if (!models.isEmpty()) {
                    return DiscoveryResult.ok(models, "gemini CLI");
                }
            }
            String fallbackKey = resolveFallbackApiKey(type, apiKeyOverride);
            if (!fallbackKey.isBlank()) {
                return fetchGeminiModels(config.effectiveBaseUrl(type), fallbackKey, false, isCancelled);
            }
            String err = jsonRes.launchError() != null ? jsonRes.launchError()
                    : CliModelClient.extractCliErrorDetail(type, exe, jsonRes.stderr(), jsonRes.stdout());
            return DiscoveryResult.error(err.isBlank()
                    ? "Could not list Gemini CLI models. Sign in first or click Refresh."
                    : err);
        }

        if ("agy".equals(canonical)) {
            CliModelClient.CliCommandResult res = cliRunner.run(exe, List.of("models"), CLI_TIMEOUT_SECONDS, isCancelled);
            if (res.launchError() == null && res.exitCode() == 0) {
                List<DiscoveredModel> models = parseAgyCliModelsText(res.stdout(), type);
                if (!models.isEmpty()) {
                    return DiscoveryResult.ok(models, "agy CLI");
                }
            }
            String err = res.launchError() != null ? res.launchError()
                    : CliModelClient.extractCliErrorDetail(type, exe, res.stderr(), res.stdout());
            return DiscoveryResult.error(err.isBlank()
                    ? "Could not list models from agy CLI. Sign in via agy first."
                    : err);
        }

        if ("codex".equals(canonical)) {
            CliModelClient.CliCommandResult res = cliRunner.run(exe, List.of("debug", "models"), CLI_TIMEOUT_SECONDS, isCancelled);
            if (res.launchError() == null && res.exitCode() == 0) {
                List<DiscoveredModel> models = parseCodexDebugModelsJson(res.stdout());
                if (!models.isEmpty()) {
                    return DiscoveryResult.ok(models, "codex CLI");
                }
            }
            String fallbackKey = resolveFallbackApiKey(type, apiKeyOverride);
            if (!fallbackKey.isBlank()) {
                return fetchOpenAiModels(config.effectiveBaseUrl(type), fallbackKey, isCancelled);
            }
            String err = res.launchError() != null ? res.launchError()
                    : CliModelClient.extractCliErrorDetail(type, exe, res.stderr(), res.stdout());
            return DiscoveryResult.error(err.isBlank()
                    ? "Could not list Codex CLI models. Sign in first or click Refresh."
                    : err);
        }

        if ("claude".equals(canonical)) {
            // If an Anthropic API key or OAuth session is available, fetch the exact live model catalog from /v1/models
            String apiKey = resolveFallbackApiKey(type, apiKeyOverride);
            if (!apiKey.isBlank()) {
                DiscoveryResult apiRes = fetchAnthropicModels(
                        config.effectiveBaseUrl(type), apiKey, config.effectiveAnthropicVersion(), false, isCancelled);
                if (apiRes.success() && !apiRes.models().isEmpty()) {
                    List<DiscoveredModel> withAuto = new ArrayList<>();
                    withAuto.add(DiscoveredModel.auto("Auto (Claude CLI default)"));
                    withAuto.addAll(apiRes.models());
                    return DiscoveryResult.ok(withAuto, "Anthropic API (/v1/models)");
                }
            }
            // Claude CLI does not expose a shell model-listing subcommand; verify auth status and use CLI default
            CliModelClient.CliCommandResult statusRes = cliRunner.run(exe, List.of("auth", "status", "--json"), 8, isCancelled);
            if (statusRes.launchError() != null) {
                return DiscoveryResult.error(statusRes.launchError());
            }
            if (statusRes.exitCode() == 0 && isClaudeLoggedInJson(statusRes.stdout())) {
                return DiscoveryResult.ok(
                        List.of(DiscoveredModel.auto("Auto (Claude CLI default)")),
                        "claude CLI (account default)"
                );
            }
            return DiscoveryResult.error("Claude CLI is not signed in. Click 'Sign In in Browser' first.");
        }

        // Custom CLI executable: fall back to provider REST API if an API key is configured, otherwise allow auto
        String fallbackKey = resolveFallbackApiKey(type, apiKeyOverride);
        if (!fallbackKey.isBlank()) {
            return discoverFromRestApi(type, config, fallbackKey, false, isCancelled);
        }
        return DiscoveryResult.ok(
                List.of(DiscoveredModel.auto("Auto (" + exe + " default)")),
                exe + " CLI"
        );
    }

    // ---- Endpoint URL builders ----

    public static String buildAnthropicModelsUrl(String baseUrl, String afterId) {
        String base = trimTrailingSlashes(baseUrl == null || baseUrl.isBlank()
                ? ModelProviderType.CLAUDE.getDefaultBaseUrl() : baseUrl);
        if (base.endsWith("/messages")) {
            base = base.substring(0, base.length() - "/messages".length());
        }
        String endpoint;
        if (base.endsWith("/models")) {
            endpoint = base;
        } else if (base.endsWith("/v1")) {
            endpoint = base + "/models";
        } else {
            endpoint = base + "/v1/models";
        }
        StringBuilder sb = new StringBuilder(endpoint).append("?limit=100");
        if (afterId != null && !afterId.isBlank()) {
            sb.append("&after_id=").append(URLEncoder.encode(afterId.trim(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public static String buildOpenAiModelsUrl(String baseUrl) {
        String base = trimTrailingSlashes(baseUrl == null || baseUrl.isBlank()
                ? ModelProviderType.CHATGPT.getDefaultBaseUrl() : baseUrl);
        if (base.endsWith("/responses")) {
            base = base.substring(0, base.length() - "/responses".length());
        } else if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        if (base.endsWith("/models")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/models";
        }
        return base + "/v1/models";
    }

    public static String buildGeminiModelsUrl(String baseUrl, String pageToken) {
        String base = trimTrailingSlashes(baseUrl == null || baseUrl.isBlank()
                ? ModelProviderType.GEMINI.getDefaultBaseUrl() : baseUrl);
        if (base.endsWith("/interactions")) {
            base = base.substring(0, base.length() - "/interactions".length());
        }
        String endpoint;
        if (base.endsWith("/models")) {
            endpoint = base;
        } else if (base.endsWith("/v1beta") || base.endsWith("/v1")) {
            endpoint = base + "/models";
        } else {
            endpoint = base + "/v1beta/models";
        }
        StringBuilder sb = new StringBuilder(endpoint).append("?pageSize=100");
        if (pageToken != null && !pageToken.isBlank()) {
            sb.append("&pageToken=").append(URLEncoder.encode(pageToken.trim(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ---- Response parsers (public for unit testing) ----

    public record AnthropicModelsPage(List<DiscoveredModel> models, boolean hasMore, String lastId) {
    }

    public static AnthropicModelsPage parseAnthropicModelsPage(String json) {
        List<DiscoveredModel> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return new AnthropicModelsPage(out, false, "");
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("data") && root.get("data").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("data")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    String id = HttpTransport.getString(obj, "id");
                    if (id.isBlank()) continue;
                    String displayName = HttpTransport.getString(obj, "display_name");
                    out.add(new DiscoveredModel(id, displayName));
                }
            }
            boolean hasMore = root.has("has_more") && !root.get("has_more").isJsonNull()
                    && root.get("has_more").getAsBoolean();
            String lastId = HttpTransport.getString(root, "last_id");
            return new AnthropicModelsPage(out, hasMore, lastId);
        } catch (Exception e) {
            return new AnthropicModelsPage(List.of(), false, "");
        }
    }

    public static List<DiscoveredModel> parseOpenAiModelsJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        record Entry(String id, long created) {
        }
        List<Entry> all = new ArrayList<>();
        List<Entry> filtered = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray data = (root.has("data") && root.get("data").isJsonArray())
                    ? root.getAsJsonArray("data")
                    : null;
            if (data == null) return List.of();
            for (JsonElement el : data) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String id = HttpTransport.getString(obj, "id");
                if (id.isBlank()) continue;
                long created = HttpTransport.getLong(obj, "created");
                Entry entry = new Entry(id, created);
                all.add(entry);
                if (!isExcludedOpenAiUtilityModel(id)) {
                    filtered.add(entry);
                }
            }
        } catch (Exception e) {
            return List.of();
        }

        // If every model was filtered (e.g., custom OpenAI-compatible gateway), keep all models
        List<Entry> chosen = filtered.isEmpty() ? all : filtered;
        chosen.sort(Comparator.comparingLong(Entry::created).reversed().thenComparing(Entry::id));

        Map<String, DiscoveredModel> dedup = new LinkedHashMap<>();
        for (Entry e : chosen) {
            dedup.putIfAbsent(e.id(), new DiscoveredModel(e.id(), e.id()));
        }
        return new ArrayList<>(dedup.values());
    }

    public record GeminiModelsPage(List<DiscoveredModel> models, String nextPageToken) {
    }

    public static GeminiModelsPage parseGeminiModelsPage(String json) {
        if (json == null || json.isBlank()) {
            return new GeminiModelsPage(List.of(), "");
        }
        List<DiscoveredModel> preferred = new ArrayList<>();
        List<DiscoveredModel> fallbackAll = new ArrayList<>();
        String nextPageToken = "";
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            nextPageToken = HttpTransport.getString(root, "nextPageToken");
            if (root.has("models") && root.get("models").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("models")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    String rawName = HttpTransport.getString(obj, "name");
                    if (rawName.isBlank()) {
                        rawName = HttpTransport.getString(obj, "baseModelId");
                    }
                    if (rawName.isBlank()) continue;
                    String id = rawName.startsWith("models/") ? rawName.substring("models/".length()) : rawName;
                    String displayName = HttpTransport.getString(obj, "displayName");
                    DiscoveredModel model = new DiscoveredModel(id, displayName);
                    fallbackAll.add(model);

                    boolean supportsGenerate = true;
                    if (obj.has("supportedGenerationMethods") && obj.get("supportedGenerationMethods").isJsonArray()) {
                        supportsGenerate = false;
                        for (JsonElement mEl : obj.getAsJsonArray("supportedGenerationMethods")) {
                            if (mEl.isJsonPrimitive() && "generateContent".equalsIgnoreCase(mEl.getAsString())) {
                                supportsGenerate = true;
                                break;
                            }
                        }
                    }
                    if (supportsGenerate && !isExcludedGeminiNonChatModel(id)) {
                        preferred.add(model);
                    }
                }
            }
        } catch (Exception e) {
            return new GeminiModelsPage(List.of(), "");
        }
        return new GeminiModelsPage(preferred.isEmpty() ? fallbackAll : preferred, nextPageToken);
    }

    public static List<DiscoveredModel> parseGeminiCliModelsJson(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        String cleaned = CliModelClient.stripAnsiAndControl(stdout).trim();
        int arrStart = cleaned.indexOf('[');
        int arrEnd = cleaned.lastIndexOf(']');
        if (arrStart < 0 || arrEnd <= arrStart) {
            return List.of();
        }
        Map<String, DiscoveredModel> out = new LinkedHashMap<>();
        try {
            JsonArray arr = JsonParser.parseString(cleaned.substring(arrStart, arrEnd + 1)).getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String id = HttpTransport.getString(obj, "modelId");
                if (id.isBlank()) id = HttpTransport.getString(obj, "id");
                if (id.isBlank()) continue;
                String display = HttpTransport.getString(obj, "displayName");
                if (display.isBlank()) display = HttpTransport.getString(obj, "name");
                out.putIfAbsent(id, new DiscoveredModel(id, display));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return new ArrayList<>(out.values());
    }

    public static List<DiscoveredModel> parseGeminiCliModelsText(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        String cleaned = CliModelClient.stripAnsiAndControl(stdout);
        Map<String, DiscoveredModel> out = new LinkedHashMap<>();
        for (String line : cleaned.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("Available ") || trimmed.startsWith("Description:")
                    || trimmed.startsWith("Context limit:")) {
                continue;
            }
            Matcher m = GEMINI_TEXT_MODEL_LINE.matcher(trimmed);
            if (m.matches()) {
                String id = m.group(1).trim();
                String display = m.group(2).trim();
                out.putIfAbsent(id, new DiscoveredModel(id, display));
            }
        }
        return new ArrayList<>(out.values());
    }

    public static List<DiscoveredModel> parseAgyCliModelsText(String stdout, ModelProviderType provider) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        String cleaned = CliModelClient.stripAnsiAndControl(stdout);
        List<DiscoveredModel> primary = new ArrayList<>();
        List<DiscoveredModel> secondary = new ArrayList<>();
        Map<String, DiscoveredModel> seen = new LinkedHashMap<>();

        for (String rawLine : cleaned.split("\\R")) {
            // Strip carriage-return spinner fragments keeping the final segment on the line
            int lastCr = rawLine.lastIndexOf('\r');
            String line = lastCr >= 0 ? rawLine.substring(lastCr + 1) : rawLine;
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.contains("Fetching available models")) {
                continue;
            }
            Matcher m = AGY_MODEL_LINE.matcher(line);
            if (!m.matches()) continue;
            String id = m.group(1).trim();
            String display = m.group(2).trim();
            if (id.equalsIgnoreCase("Usage") || id.equalsIgnoreCase("Flags") || seen.containsKey(id)) {
                continue;
            }
            DiscoveredModel model = new DiscoveredModel(id, display);
            seen.put(id, model);
            String lower = id.toLowerCase(Locale.ROOT);
            boolean matchesProvider = switch (provider) {
                case GEMINI -> lower.startsWith("gemini");
                case CLAUDE -> lower.startsWith("claude");
                case CHATGPT -> lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4");
            };
            if (matchesProvider) {
                primary.add(model);
            } else {
                secondary.add(model);
            }
        }

        List<DiscoveredModel> ordered = new ArrayList<>(primary.size() + secondary.size());
        ordered.addAll(primary);
        ordered.addAll(secondary);
        return ordered;
    }

    public static List<DiscoveredModel> parseCodexDebugModelsJson(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        String cleaned = CliModelClient.stripAnsiAndControl(stdout).trim();
        int objStart = cleaned.indexOf('{');
        int objEnd = cleaned.lastIndexOf('}');
        if (objStart < 0 || objEnd <= objStart) {
            return List.of();
        }
        record Entry(DiscoveredModel model, int priority) {
        }
        List<Entry> visible = new ArrayList<>();
        List<Entry> all = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(cleaned.substring(objStart, objEnd + 1)).getAsJsonObject();
            JsonArray arr = (root.has("models") && root.get("models").isJsonArray())
                    ? root.getAsJsonArray("models")
                    : null;
            if (arr == null) return List.of();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String slug = HttpTransport.getString(obj, "slug");
                if (slug.isBlank()) slug = HttpTransport.getString(obj, "id");
                if (slug.isBlank()) continue;
                String display = HttpTransport.getString(obj, "display_name");
                String visibility = HttpTransport.getString(obj, "visibility");
                int priority = (int) HttpTransport.getLong(obj, "priority");
                if (priority <= 0) priority = Integer.MAX_VALUE;
                Entry entry = new Entry(new DiscoveredModel(slug, display), priority);
                all.add(entry);
                if (!"hide".equalsIgnoreCase(visibility)) {
                    visible.add(entry);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        List<Entry> chosen = visible.isEmpty() ? all : visible;
        chosen.sort(Comparator.comparingInt(Entry::priority).thenComparing(e -> e.model().id()));

        Map<String, DiscoveredModel> dedup = new LinkedHashMap<>();
        for (Entry e : chosen) {
            dedup.putIfAbsent(e.model().id(), e.model());
        }
        return new ArrayList<>(dedup.values());
    }

    public static boolean isClaudeLoggedInJson(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return false;
        }
        String cleaned = CliModelClient.stripAnsiAndControl(stdout).trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return false;
        }
        try {
            JsonObject obj = JsonParser.parseString(cleaned.substring(start, end + 1)).getAsJsonObject();
            return obj.has("loggedIn") && !obj.get("loggedIn").isJsonNull() && obj.get("loggedIn").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    // ---- Internal helpers ----

    private static boolean isExcludedOpenAiUtilityModel(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("embedding")
                || lower.startsWith("whisper")
                || lower.startsWith("tts-")
                || lower.contains("-tts")
                || lower.startsWith("dall-e")
                || lower.contains("moderation")
                || lower.startsWith("babbage")
                || lower.startsWith("davinci")
                || lower.startsWith("curie")
                || lower.startsWith("ada")
                || lower.contains("transcribe")
                || lower.startsWith("sora")
                || lower.contains("-image")
                || lower.startsWith("gpt-image")
                || lower.startsWith("chatgpt-image")
                || lower.contains("realtime")
                || lower.contains("-audio");
    }

    private static boolean isExcludedGeminiNonChatModel(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("embedding")
                || lower.equals("aqa")
                || lower.startsWith("imagen")
                || lower.startsWith("veo")
                || lower.startsWith("lyria")
                || lower.contains("-tts")
                || lower.contains("transcribe")
                || lower.contains("image-generation");
    }

    private static List<DiscoveredModel> sortGeminiModels(List<DiscoveredModel> models) {
        models.sort((a, b) -> {
            int cmp = compareVersionDescending(a.id(), b.id());
            if (cmp != 0) return cmp;
            return a.id().compareToIgnoreCase(b.id());
        });
        return models;
    }

    private static int compareVersionDescending(String idA, String idB) {
        boolean gemA = idA.toLowerCase(Locale.ROOT).startsWith("gemini");
        boolean gemB = idB.toLowerCase(Locale.ROOT).startsWith("gemini");
        if (gemA != gemB) return gemA ? -1 : 1;

        int[] verA = extractMajorMinor(idA);
        int[] verB = extractMajorMinor(idB);
        if (verA[0] != verB[0]) return Integer.compare(verB[0], verA[0]);
        if (verA[1] != verB[1]) return Integer.compare(verB[1], verA[1]);
        return 0;
    }

    private static int[] extractMajorMinor(String id) {
        Matcher m = VERSION_NUMBER_PATTERN.matcher(id);
        if (m.find()) {
            int major = parseIntSafe(m.group(1));
            int minor = m.group(2) != null ? parseIntSafe(m.group(2)) : 0;
            return new int[]{major, minor};
        }
        return new int[]{0, 0};
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String resolveApiCredential(
            ModelProviderType type,
            ProviderConfig cfg,
            String apiKeyOverride,
            boolean useBearerAuth,
            BooleanSupplier isCancelled
    ) {
        if (useBearerAuth) {
            OAuthToken token = OAuthSessionStore.getToken(type);
            if (token == null || !token.hasAccessToken()) {
                return "";
            }
            if (token.isExpired()) {
                if (token.canRefresh()) {
                    String secret = SecretStorageService.getOAuthClientSecret(type);
                    OAuthPkceService.AuthResult refreshed = new OAuthPkceService()
                            .refreshAccessToken(type, cfg, secret, token, isCancelled);
                    if (refreshed.success() && refreshed.token() != null) {
                        return refreshed.token().accessToken();
                    }
                }
                return "";
            }
            return token.accessToken();
        }
        return resolveFallbackApiKey(type, apiKeyOverride);
    }

    private static String resolveFallbackApiKey(ModelProviderType type, String apiKeyOverride) {
        if (apiKeyOverride != null && !apiKeyOverride.isBlank()) {
            return apiKeyOverride.trim();
        }
        return SecretStorageService.resolveApiKey(type);
    }

    private static CliModelClient.CliCommandResult defaultCliRunner(
            String executable,
            List<String> args,
            int timeoutSeconds,
            BooleanSupplier isCancelled
    ) {
        String resolved = CliModelClient.resolveExecutable(executable);
        if (resolved == null) {
            return new CliModelClient.CliCommandResult(-1, "", "", false, false,
                    "CLI '" + executable + "' is not installed.");
        }
        return CliModelClient.runUtilityCommand(resolved, args, timeoutSeconds, isCancelled);
    }

    private static String cacheKey(ModelProviderType type, ConnectionMode mode, String target, String credHash) {
        return type.name() + "|" + mode.name() + "|" + (target == null ? "" : target.trim()) + "|" + credHash;
    }

    private static String sha256Short(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String trimTrailingSlashes(String s) {
        String res = s.trim();
        while (res.endsWith("/")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }
}
