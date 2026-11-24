package io.github.obelisk121.trimodel.provider;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.obelisk121.trimodel.model.ChatMessage;
import io.github.obelisk121.trimodel.model.GeminiApiStyle;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.ModelRequest;
import io.github.obelisk121.trimodel.model.ModelResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Google Gemini API client supporting both the modern Interactions API (/v1beta/interactions,
 * with steps[] and legacy outputs[] response support) and classic generateContent API.
 */
public final class GeminiClient implements ModelClient {
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final String apiKey;
    private final String modelId;
    private final GeminiApiStyle apiStyle;
    private final HttpTransport.PostFunction transport;

    public GeminiClient(String baseUrl, String apiKey, String modelId, GeminiApiStyle apiStyle) {
        this(baseUrl, apiKey, modelId, apiStyle, HttpTransport::postJson);
    }

    public GeminiClient(
            String baseUrl,
            String apiKey,
            String modelId,
            GeminiApiStyle apiStyle,
            HttpTransport.PostFunction transport
    ) {
        this.baseUrl = trimTrailingSlashes(baseUrl == null || baseUrl.isBlank()
                ? ModelProviderType.GEMINI.getDefaultBaseUrl() : baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelId = (modelId == null || modelId.isBlank())
                ? ModelProviderType.GEMINI.getDefaultModel() : modelId.trim();
        this.apiStyle = apiStyle == null ? GeminiApiStyle.INTERACTIONS : apiStyle;
        this.transport = transport == null ? HttpTransport::postJson : transport;
    }

    @Override
    public ModelProviderType getProviderType() {
        return ModelProviderType.GEMINI;
    }

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public ModelResponse generate(ModelRequest request, BooleanSupplier isCancelled) {
        if (apiKey.isBlank()) {
            return ModelResponse.error(ModelProviderType.GEMINI, modelId,
                    "Gemini API key is not configured. Add it in Settings -> Tools -> Tri-Model AI Orchestrator or set GEMINI_API_KEY.", 0);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-goog-api-key", apiKey);

        String endpoint = apiStyle == GeminiApiStyle.INTERACTIONS
                ? buildInteractionsEndpoint(baseUrl)
                : buildGenerateContentEndpoint(baseUrl, modelId);

        JsonObject payload = apiStyle == GeminiApiStyle.INTERACTIONS
                ? buildInteractionsPayload(request)
                : buildGenerateContentPayload(request);

        HttpTransport.HttpResult result = transport.post(
                endpoint, headers, GSON.toJson(payload), request.timeoutSeconds(), isCancelled);

        if (result.cancelled()) {
            return ModelResponse.error(ModelProviderType.GEMINI, modelId, "Cancelled", result.latencyMs());
        }
        if (result.transportError() != null) {
            return ModelResponse.error(ModelProviderType.GEMINI, modelId, result.transportError(), result.latencyMs());
        }
        if (!result.is2xx()) {
            return ModelResponse.error(ModelProviderType.GEMINI, modelId,
                    HttpTransport.extractErrorDetail(result.statusCode(), result.body()), result.latencyMs());
        }

        return apiStyle == GeminiApiStyle.INTERACTIONS
                ? parseInteractionsOutput(result.body(), result.latencyMs())
                : parseGenerateContentOutput(result.body(), result.latencyMs());
    }

    JsonObject buildInteractionsPayload(ModelRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", modelId);
        root.addProperty("store", false);

        if (!request.systemPrompt().isBlank()) {
            root.addProperty("system_instruction", request.systemPrompt());
        }

        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("max_output_tokens", request.maxOutputTokens());
        root.add("generation_config", genConfig);

        if (request.history().isEmpty()) {
            root.addProperty("input", request.userPrompt());
        } else {
            JsonArray turns = new JsonArray();
            for (ChatMessage msg : request.history()) {
                if (msg.content() == null || msg.content().isBlank()) continue;
                JsonObject turn = new JsonObject();
                turn.addProperty("role", "assistant".equalsIgnoreCase(msg.role()) ? "model" : "user");
                JsonArray content = new JsonArray();
                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", msg.content());
                content.add(textPart);
                turn.add("content", content);
                turns.add(turn);
            }
            JsonObject current = new JsonObject();
            current.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            textPart.addProperty("text", request.userPrompt());
            content.add(textPart);
            current.add("content", content);
            turns.add(current);
            root.add("input", turns);
        }
        return root;
    }

    JsonObject buildGenerateContentPayload(ModelRequest request) {
        JsonObject root = new JsonObject();

        if (!request.systemPrompt().isBlank()) {
            JsonObject sysInstruction = new JsonObject();
            JsonArray sysParts = new JsonArray();
            JsonObject sysText = new JsonObject();
            sysText.addProperty("text", request.systemPrompt());
            sysParts.add(sysText);
            sysInstruction.add("parts", sysParts);
            root.add("systemInstruction", sysInstruction);
        }

        JsonArray contents = new JsonArray();
        for (ChatMessage msg : request.history()) {
            if (msg.content() == null || msg.content().isBlank()) continue;
            contents.add(buildContentTurn("assistant".equalsIgnoreCase(msg.role()) ? "model" : "user", msg.content()));
        }
        contents.add(buildContentTurn("user", request.userPrompt()));
        root.add("contents", contents);

        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("maxOutputTokens", request.maxOutputTokens());
        root.add("generationConfig", genConfig);
        return root;
    }

    private static JsonObject buildContentTurn(String role, String text) {
        JsonObject turn = new JsonObject();
        turn.addProperty("role", role);
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        parts.add(part);
        turn.add("parts", parts);
        return turn;
    }

    ModelResponse parseInteractionsOutput(String body, long latencyMs) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String resolvedModel = HttpTransport.getString(root, "model");
            if (resolvedModel.isBlank()) {
                resolvedModel = modelId;
            }

            StringBuilder textOut = new StringBuilder();
            StringBuilder thinkingOut = new StringBuilder();

            // Modern schema (May 2026+): steps[] containing model_output and thought entries
            if (root.has("steps") && root.get("steps").isJsonArray()) {
                for (JsonElement stepEl : root.getAsJsonArray("steps")) {
                    if (!stepEl.isJsonObject()) continue;
                    JsonObject step = stepEl.getAsJsonObject();
                    String type = HttpTransport.getString(step, "type");
                    if ("model_output".equals(type) && step.has("content") && step.get("content").isJsonArray()) {
                        for (JsonElement blockEl : step.getAsJsonArray("content")) {
                            if (!blockEl.isJsonObject()) continue;
                            JsonObject block = blockEl.getAsJsonObject();
                            if ("text".equals(HttpTransport.getString(block, "type"))) {
                                String t = HttpTransport.getString(block, "text");
                                if (!t.isEmpty()) {
                                    if (!textOut.isEmpty()) textOut.append("\n");
                                    textOut.append(t);
                                }
                            }
                        }
                    } else if ("thought".equals(type) && step.has("summary") && step.get("summary").isJsonArray()) {
                        for (JsonElement sumEl : step.getAsJsonArray("summary")) {
                            if (sumEl.isJsonObject()) {
                                String t = HttpTransport.getString(sumEl.getAsJsonObject(), "text");
                                if (!t.isEmpty()) {
                                    if (!thinkingOut.isEmpty()) thinkingOut.append("\n");
                                    thinkingOut.append(t);
                                }
                            }
                        }
                    }
                }
            }

            // Legacy Interactions fallback: outputs[]
            if (textOut.isEmpty() && root.has("outputs") && root.get("outputs").isJsonArray()) {
                for (JsonElement outEl : root.getAsJsonArray("outputs")) {
                    if (!outEl.isJsonObject()) continue;
                    JsonObject outObj = outEl.getAsJsonObject();
                    String type = HttpTransport.getString(outObj, "type");
                    if ("text".equals(type) || outObj.has("text")) {
                        String t = HttpTransport.getString(outObj, "text");
                        if (!t.isEmpty()) {
                            if (!textOut.isEmpty()) textOut.append("\n");
                            textOut.append(t);
                        }
                    }
                }
            }

            // Top-level convenience field fallback
            if (textOut.isEmpty()) {
                String direct = HttpTransport.getString(root, "output_text");
                if (!direct.isBlank()) {
                    textOut.append(direct);
                }
            }

            long inputTokens = 0;
            long outputTokens = 0;
            if (root.has("usage") && root.get("usage").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usage");
                inputTokens = firstNonZero(
                        HttpTransport.getLong(usage, "total_input_tokens"),
                        HttpTransport.getLong(usage, "input_tokens"),
                        HttpTransport.getLong(usage, "prompt_token_count")
                );
                outputTokens = firstNonZero(
                        HttpTransport.getLong(usage, "total_output_tokens"),
                        HttpTransport.getLong(usage, "output_tokens"),
                        HttpTransport.getLong(usage, "candidates_token_count")
                );
            }

            if (textOut.isEmpty()) {
                return ModelResponse.error(ModelProviderType.GEMINI, resolvedModel,
                        "Gemini Interactions API returned no text content.", latencyMs);
            }
            return ModelResponse.ok(ModelProviderType.GEMINI, resolvedModel,
                    textOut.toString(), thinkingOut.toString(), inputTokens, outputTokens, latencyMs);
        } catch (Exception e) {
            return ModelResponse.error(ModelProviderType.GEMINI, modelId,
                    "Failed to parse Gemini Interactions response: " + e.getMessage(), latencyMs);
        }
    }

    ModelResponse parseGenerateContentOutput(String body, long latencyMs) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            StringBuilder textOut = new StringBuilder();
            StringBuilder thinkingOut = new StringBuilder();

            if (root.has("candidates") && root.get("candidates").isJsonArray()
                    && !root.getAsJsonArray("candidates").isEmpty()) {
                JsonObject first = root.getAsJsonArray("candidates").get(0).getAsJsonObject();
                if (first.has("content") && first.get("content").isJsonObject()) {
                    JsonObject content = first.getAsJsonObject("content");
                    if (content.has("parts") && content.get("parts").isJsonArray()) {
                        for (JsonElement partEl : content.getAsJsonArray("parts")) {
                            if (!partEl.isJsonObject()) continue;
                            JsonObject part = partEl.getAsJsonObject();
                            String t = HttpTransport.getString(part, "text");
                            boolean isThought = part.has("thought") && !part.get("thought").isJsonNull()
                                    && part.get("thought").getAsBoolean();
                            if (!t.isEmpty()) {
                                if (isThought) {
                                    if (!thinkingOut.isEmpty()) thinkingOut.append("\n");
                                    thinkingOut.append(t);
                                } else {
                                    if (!textOut.isEmpty()) textOut.append("\n");
                                    textOut.append(t);
                                }
                            }
                        }
                    }
                }
            }

            long inputTokens = 0;
            long outputTokens = 0;
            if (root.has("usageMetadata") && root.get("usageMetadata").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usageMetadata");
                inputTokens = HttpTransport.getLong(usage, "promptTokenCount");
                outputTokens = HttpTransport.getLong(usage, "candidatesTokenCount");
            }

            if (textOut.isEmpty()) {
                return ModelResponse.error(ModelProviderType.GEMINI, modelId,
                        "Gemini generateContent returned no text content.", latencyMs);
            }
            return ModelResponse.ok(ModelProviderType.GEMINI, modelId,
                    textOut.toString(), thinkingOut.toString(), inputTokens, outputTokens, latencyMs);
        } catch (Exception e) {
            return ModelResponse.error(ModelProviderType.GEMINI, modelId,
                    "Failed to parse Gemini generateContent response: " + e.getMessage(), latencyMs);
        }
    }

    private static long firstNonZero(long... vals) {
        for (long v : vals) {
            if (v > 0) return v;
        }
        return 0L;
    }

    private static String buildInteractionsEndpoint(String base) {
        if (base.endsWith("/interactions")) return base;
        if (base.endsWith("/v1beta") || base.endsWith("/v1")) return base + "/interactions";
        return base + "/v1beta/interactions";
    }

    private static String buildGenerateContentEndpoint(String base, String model) {
        String encoded = URLEncoder.encode(model, StandardCharsets.UTF_8);
        if (base.endsWith("/models")) return base + "/" + encoded + ":generateContent";
        if (base.endsWith("/v1beta") || base.endsWith("/v1")) return base + "/models/" + encoded + ":generateContent";
        return base + "/v1beta/models/" + encoded + ":generateContent";
    }

    private static String trimTrailingSlashes(String s) {
        String res = s.trim();
        while (res.endsWith("/")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }
}
