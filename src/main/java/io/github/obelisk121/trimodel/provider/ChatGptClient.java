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
import io.github.obelisk121.trimodel.model.OpenAiApiStyle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * OpenAI ChatGPT API client supporting both the modern Responses API (/v1/responses)
 * and Chat Completions API (/v1/chat/completions) for OpenAI-compatible gateways.
 */
public final class ChatGptClient implements ModelClient {
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final String apiKey;
    private final String modelId;
    private final OpenAiApiStyle apiStyle;
    private final HttpTransport.PostFunction transport;

    public ChatGptClient(String baseUrl, String apiKey, String modelId, OpenAiApiStyle apiStyle) {
        this(baseUrl, apiKey, modelId, apiStyle, HttpTransport::postJson);
    }

    public ChatGptClient(
            String baseUrl,
            String apiKey,
            String modelId,
            OpenAiApiStyle apiStyle,
            HttpTransport.PostFunction transport
    ) {
        this.baseUrl = trimTrailingSlashes(baseUrl == null || baseUrl.isBlank()
                ? ModelProviderType.CHATGPT.getDefaultBaseUrl() : baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelId = (modelId == null || modelId.isBlank())
                ? ModelProviderType.CHATGPT.getDefaultModel() : modelId.trim();
        this.apiStyle = apiStyle == null ? OpenAiApiStyle.RESPONSES : apiStyle;
        this.transport = transport == null ? HttpTransport::postJson : transport;
    }

    @Override
    public ModelProviderType getProviderType() {
        return ModelProviderType.CHATGPT;
    }

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public ModelResponse generate(ModelRequest request, BooleanSupplier isCancelled) {
        if (apiKey.isBlank()) {
            return ModelResponse.error(ModelProviderType.CHATGPT, modelId,
                    "OpenAI API key is not configured. Add it in Settings -> Tools -> Tri-Model AI Orchestrator or set OPENAI_API_KEY.", 0);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);

        String endpoint = apiStyle == OpenAiApiStyle.RESPONSES
                ? buildResponsesEndpoint(baseUrl)
                : buildChatCompletionsEndpoint(baseUrl);

        JsonObject payload = apiStyle == OpenAiApiStyle.RESPONSES
                ? buildResponsesPayload(request)
                : buildChatCompletionsPayload(request);

        HttpTransport.HttpResult result = transport.post(
                endpoint, headers, GSON.toJson(payload), request.timeoutSeconds(), isCancelled);

        if (result.cancelled()) {
            return ModelResponse.error(ModelProviderType.CHATGPT, modelId, "Cancelled", result.latencyMs());
        }
        if (result.transportError() != null) {
            return ModelResponse.error(ModelProviderType.CHATGPT, modelId, result.transportError(), result.latencyMs());
        }
        if (!result.is2xx()) {
            return ModelResponse.error(ModelProviderType.CHATGPT, modelId,
                    HttpTransport.extractErrorDetail(result.statusCode(), result.body()), result.latencyMs());
        }

        return apiStyle == OpenAiApiStyle.RESPONSES
                ? parseResponsesOutput(result.body(), result.latencyMs())
                : parseChatCompletionsOutput(result.body(), result.latencyMs());
    }

    JsonObject buildResponsesPayload(ModelRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", modelId);
        root.addProperty("store", false);
        root.addProperty("max_output_tokens", request.maxOutputTokens());

        if (!request.systemPrompt().isBlank()) {
            root.addProperty("instructions", request.systemPrompt());
        }

        if (request.history().isEmpty()) {
            root.addProperty("input", request.userPrompt());
        } else {
            JsonArray inputItems = new JsonArray();
            for (ChatMessage msg : request.history()) {
                if (msg.content() == null || msg.content().isBlank()) continue;
                JsonObject item = new JsonObject();
                item.addProperty("role", "assistant".equalsIgnoreCase(msg.role()) ? "assistant" : "user");
                item.addProperty("content", msg.content());
                inputItems.add(item);
            }
            JsonObject current = new JsonObject();
            current.addProperty("role", "user");
            current.addProperty("content", request.userPrompt());
            inputItems.add(current);
            root.add("input", inputItems);
        }
        return root;
    }

    JsonObject buildChatCompletionsPayload(ModelRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", modelId);
        root.addProperty("max_completion_tokens", request.maxOutputTokens());

        JsonArray messages = new JsonArray();
        if (!request.systemPrompt().isBlank()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", request.systemPrompt());
            messages.add(sys);
        }
        for (ChatMessage msg : request.history()) {
            if (msg.content() == null || msg.content().isBlank()) continue;
            JsonObject m = new JsonObject();
            m.addProperty("role", "assistant".equalsIgnoreCase(msg.role()) ? "assistant" : "user");
            m.addProperty("content", msg.content());
            messages.add(m);
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", request.userPrompt());
        messages.add(user);
        root.add("messages", messages);
        return root;
    }

    ModelResponse parseResponsesOutput(String body, long latencyMs) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String resolvedModel = HttpTransport.getString(root, "model");
            if (resolvedModel.isBlank()) {
                resolvedModel = modelId;
            }

            StringBuilder textOut = new StringBuilder();
            StringBuilder thinkingOut = new StringBuilder();

            String topOutputText = HttpTransport.getString(root, "output_text");
            if (!topOutputText.isBlank()) {
                textOut.append(topOutputText);
            }

            if (root.has("output") && root.get("output").isJsonArray()) {
                for (JsonElement itemEl : root.getAsJsonArray("output")) {
                    if (!itemEl.isJsonObject()) continue;
                    JsonObject item = itemEl.getAsJsonObject();
                    String type = HttpTransport.getString(item, "type");
                    if ("message".equals(type) && item.has("content") && item.get("content").isJsonArray()) {
                        if (topOutputText.isBlank()) {
                            for (JsonElement partEl : item.getAsJsonArray("content")) {
                                if (!partEl.isJsonObject()) continue;
                                JsonObject part = partEl.getAsJsonObject();
                                String partType = HttpTransport.getString(part, "type");
                                if ("output_text".equals(partType) || "text".equals(partType)) {
                                    String t = HttpTransport.getString(part, "text");
                                    if (!t.isEmpty()) {
                                        if (!textOut.isEmpty()) textOut.append("\n");
                                        textOut.append(t);
                                    }
                                }
                            }
                        }
                    } else if ("reasoning".equals(type) && item.has("summary") && item.get("summary").isJsonArray()) {
                        for (JsonElement sumEl : item.getAsJsonArray("summary")) {
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

            long inputTokens = 0;
            long outputTokens = 0;
            if (root.has("usage") && root.get("usage").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usage");
                inputTokens = HttpTransport.getLong(usage, "input_tokens");
                outputTokens = HttpTransport.getLong(usage, "output_tokens");
            }

            if (textOut.isEmpty()) {
                return ModelResponse.error(ModelProviderType.CHATGPT, resolvedModel,
                        "OpenAI Responses API returned no text content.", latencyMs);
            }
            return ModelResponse.ok(ModelProviderType.CHATGPT, resolvedModel,
                    textOut.toString(), thinkingOut.toString(), inputTokens, outputTokens, latencyMs);
        } catch (Exception e) {
            return ModelResponse.error(ModelProviderType.CHATGPT, modelId,
                    "Failed to parse OpenAI response: " + e.getMessage(), latencyMs);
        }
    }

    ModelResponse parseChatCompletionsOutput(String body, long latencyMs) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String resolvedModel = HttpTransport.getString(root, "model");
            if (resolvedModel.isBlank()) {
                resolvedModel = modelId;
            }

            String content = "";
            if (root.has("choices") && root.get("choices").isJsonArray()
                    && !root.getAsJsonArray("choices").isEmpty()) {
                JsonObject first = root.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (first.has("message") && first.get("message").isJsonObject()) {
                    JsonObject msg = first.getAsJsonObject("message");
                    if (msg.has("content") && msg.get("content").isJsonPrimitive()) {
                        content = msg.get("content").getAsString();
                    } else if (msg.has("content") && msg.get("content").isJsonArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonElement el : msg.getAsJsonArray("content")) {
                            if (el.isJsonObject()) {
                                String t = HttpTransport.getString(el.getAsJsonObject(), "text");
                                if (!t.isEmpty()) {
                                    if (!sb.isEmpty()) sb.append("\n");
                                    sb.append(t);
                                }
                            }
                        }
                        content = sb.toString();
                    }
                }
            }

            long inputTokens = 0;
            long outputTokens = 0;
            if (root.has("usage") && root.get("usage").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usage");
                inputTokens = HttpTransport.getLong(usage, "prompt_tokens");
                outputTokens = HttpTransport.getLong(usage, "completion_tokens");
            }

            if (content.isBlank()) {
                return ModelResponse.error(ModelProviderType.CHATGPT, resolvedModel,
                        "OpenAI Chat Completions returned empty content.", latencyMs);
            }
            return ModelResponse.ok(ModelProviderType.CHATGPT, resolvedModel,
                    content, "", inputTokens, outputTokens, latencyMs);
        } catch (Exception e) {
            return ModelResponse.error(ModelProviderType.CHATGPT, modelId,
                    "Failed to parse Chat Completions response: " + e.getMessage(), latencyMs);
        }
    }

    private static String buildResponsesEndpoint(String base) {
        if (base.endsWith("/responses")) return base;
        if (base.endsWith("/v1")) return base + "/responses";
        return base + "/v1/responses";
    }

    private static String buildChatCompletionsEndpoint(String base) {
        if (base.endsWith("/chat/completions")) return base;
        if (base.endsWith("/v1")) return base + "/chat/completions";
        return base + "/v1/chat/completions";
    }

    private static String trimTrailingSlashes(String s) {
        String res = s.trim();
        while (res.endsWith("/")) {
            res = res.substring(0, res.length() - 1);
        }
        return res;
    }
}
