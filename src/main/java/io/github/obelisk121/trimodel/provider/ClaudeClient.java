package io.github.obelisk121.trimodel.provider;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.obelisk121.trimodel.model.ChatMessage;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.ModelRequest;
import io.github.obelisk121.trimodel.model.ModelResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Anthropic Claude Messages API client (POST /v1/messages).
 * Compliant with anthropic-version 2026-10-21 (does not send deprecated sampling params).
 */
public final class ClaudeClient implements ModelClient {
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final String apiKey;
    private final String modelId;
    private final String anthropicVersion;
    private final boolean useBearerAuth;
    private final HttpTransport.PostFunction transport;

    public ClaudeClient(String baseUrl, String apiKey, String modelId, String anthropicVersion) {
        this(baseUrl, apiKey, modelId, anthropicVersion, false, HttpTransport::postJson);
    }

    public ClaudeClient(
            String baseUrl,
            String apiKey,
            String modelId,
            String anthropicVersion,
            HttpTransport.PostFunction transport
    ) {
        this(baseUrl, apiKey, modelId, anthropicVersion, false, transport);
    }

    public ClaudeClient(
            String baseUrl,
            String apiKey,
            String modelId,
            String anthropicVersion,
            boolean useBearerAuth,
            HttpTransport.PostFunction transport
    ) {
        this.baseUrl = trimTrailingSlashes(baseUrl == null || baseUrl.isBlank()
                ? ModelProviderType.CLAUDE.getDefaultBaseUrl() : baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelId = (modelId == null || modelId.isBlank())
                ? ModelProviderType.CLAUDE.getDefaultModel() : modelId.trim();
        this.anthropicVersion = (anthropicVersion == null || anthropicVersion.isBlank())
                ? "2026-10-21" : anthropicVersion.trim();
        this.useBearerAuth = useBearerAuth;
        this.transport = transport == null ? HttpTransport::postJson : transport;
    }

    @Override
    public ModelProviderType getProviderType() {
        return ModelProviderType.CLAUDE;
    }

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public ModelResponse generate(ModelRequest request, BooleanSupplier isCancelled) {
        if (apiKey.isBlank()) {
            String msg = useBearerAuth
                    ? "Claude browser OAuth session is not active or has expired. Click 'Authorize in Browser' in Settings or the Tool Window."
                    : "Anthropic API key is not configured. Add it in Settings -> Tools -> Tri-Model AI Orchestrator or set ANTHROPIC_API_KEY.";
            return ModelResponse.error(ModelProviderType.CLAUDE, modelId, msg, 0);
        }

        String endpoint = buildEndpoint(baseUrl);
        JsonObject payload = buildPayload(request);

        Map<String, String> headers = new LinkedHashMap<>();
        if (useBearerAuth) {
            headers.put("Authorization", "Bearer " + apiKey);
        } else {
            headers.put("x-api-key", apiKey);
        }
        headers.put("anthropic-version", anthropicVersion);

        HttpTransport.HttpResult result = transport.post(
                endpoint, headers, GSON.toJson(payload), request.timeoutSeconds(), isCancelled);

        if (result.cancelled()) {
            return ModelResponse.error(ModelProviderType.CLAUDE, modelId, "Cancelled", result.latencyMs());
        }
        if (result.transportError() != null) {
            return ModelResponse.error(ModelProviderType.CLAUDE, modelId, result.transportError(), result.latencyMs());
        }
        if (!result.is2xx()) {
            return ModelResponse.error(ModelProviderType.CLAUDE, modelId,
                    HttpTransport.extractErrorDetail(result.statusCode(), result.body()), result.latencyMs());
        }

        return parseResponse(result.body(), result.latencyMs());
    }

    JsonObject buildPayload(ModelRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", modelId);
        root.addProperty("max_tokens", request.maxOutputTokens());

        if (!request.systemPrompt().isBlank()) {
            root.addProperty("system", request.systemPrompt());
        }

        JsonArray messages = new JsonArray();
        String lastRole = null;
        for (ChatMessage msg : request.history()) {
            if (msg.content() == null || msg.content().isBlank()) {
                continue;
            }
            String role = "assistant".equalsIgnoreCase(msg.role()) ? "assistant" : "user";
            if (messages.isEmpty() && "assistant".equals(role)) {
                continue; // Anthropic requires first message to be user
            }
            appendOrMergeMessage(messages, role, msg.content(), lastRole);
            lastRole = role;
        }

        appendOrMergeMessage(messages, "user", request.userPrompt(), lastRole);
        root.add("messages", messages);
        return root;
    }

    private static void appendOrMergeMessage(JsonArray messages, String role, String content, String lastRole) {
        if (role.equals(lastRole) && !messages.isEmpty()) {
            JsonObject prev = messages.get(messages.size() - 1).getAsJsonObject();
            String merged = prev.get("content").getAsString() + "\n\n" + content;
            prev.addProperty("content", merged);
        } else {
            JsonObject m = new JsonObject();
            m.addProperty("role", role);
            m.addProperty("content", content);
            messages.add(m);
        }
    }

    ModelResponse parseResponse(String body, long latencyMs) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String resolvedModel = HttpTransport.getString(root, "model");
            if (resolvedModel.isBlank()) {
                resolvedModel = modelId;
            }

            StringBuilder textOut = new StringBuilder();
            StringBuilder thinkingOut = new StringBuilder();
            if (root.has("content") && root.get("content").isJsonArray()) {
                for (JsonElement elem : root.getAsJsonArray("content")) {
                    if (!elem.isJsonObject()) continue;
                    JsonObject block = elem.getAsJsonObject();
                    String type = HttpTransport.getString(block, "type");
                    if ("text".equals(type)) {
                        String text = HttpTransport.getString(block, "text");
                        if (!text.isEmpty()) {
                            if (!textOut.isEmpty()) textOut.append("\n");
                            textOut.append(text);
                        }
                    } else if ("thinking".equals(type)) {
                        String thought = HttpTransport.getString(block, "thinking");
                        if (!thought.isEmpty()) {
                            if (!thinkingOut.isEmpty()) thinkingOut.append("\n");
                            thinkingOut.append(thought);
                        }
                    }
                }
            }

            long inputTokens = 0;
            long outputTokens = 0;
            if (root.has("usage") && root.get("usage").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usage");
                inputTokens = HttpTransport.getLong(usage, "input_tokens");
                outputTokens = HttpTransport.getLong(usage, "output_tokens");
            }

            if (textOut.isEmpty()) {
                return ModelResponse.error(ModelProviderType.CLAUDE, resolvedModel,
                        "Claude returned an empty response.", latencyMs);
            }
            return ModelResponse.ok(ModelProviderType.CLAUDE, resolvedModel,
                    textOut.toString(), thinkingOut.toString(), inputTokens, outputTokens, latencyMs);
        } catch (Exception e) {
            return ModelResponse.error(ModelProviderType.CLAUDE, modelId,
                    "Failed to parse Claude response: " + e.getMessage(), latencyMs);
        }
    }

    private static String buildEndpoint(String base) {
        if (base.endsWith("/messages")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/messages";
        }
        return base + "/v1/messages";
    }

    private static String trimTrailingSlashes(String s) {
        String res = s.trim();
        while (res.endsWith("/")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }
}
