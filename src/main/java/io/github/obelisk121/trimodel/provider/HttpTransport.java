package io.github.obelisk121.trimodel.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Shared HTTP JSON transport supporting cooperative cancellation and clean error extraction.
 */
public final class HttpTransport {
    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record HttpResult(int statusCode, String body, long latencyMs, boolean cancelled, String transportError) {
        public boolean is2xx() {
            return transportError == null && !cancelled && statusCode >= 200 && statusCode < 300;
        }
    }

    @FunctionalInterface
    public interface PostFunction {
        HttpResult post(
                String url,
                Map<String, String> headers,
                String jsonBody,
                int timeoutSeconds,
                BooleanSupplier isCancelled
        );
    }

    private HttpTransport() {
    }

    public static HttpResult postJson(
            String url,
            Map<String, String> headers,
            String jsonBody,
            int timeoutSeconds,
            BooleanSupplier isCancelled
    ) {
        long startNanos = System.nanoTime();
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            return new HttpResult(0, "", 0, true, "Cancelled before request started");
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }

            CompletableFuture<HttpResponse<String>> future =
                    SHARED_CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            long deadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(Math.max(10, timeoutSeconds) + 5L);
            while (true) {
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    future.cancel(true);
                    return new HttpResult(0, "", elapsedMs(startNanos), true, "Request cancelled by user");
                }
                if (System.nanoTime() > deadlineNanos) {
                    future.cancel(true);
                    return new HttpResult(0, "", elapsedMs(startNanos), false,
                            "Request timed out after " + timeoutSeconds + "s");
                }
                try {
                    HttpResponse<String> response = future.get(150, TimeUnit.MILLISECONDS);
                    return new HttpResult(response.statusCode(), response.body(), elapsedMs(startNanos), false, null);
                } catch (TimeoutException ignored) {
                    // Poll cancellation status
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HttpResult(0, "", elapsedMs(startNanos), true, "Request interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new HttpResult(0, "", elapsedMs(startNanos), false,
                    "Network error calling " + url + ": " + cause.getMessage());
        } catch (Exception e) {
            return new HttpResult(0, "", elapsedMs(startNanos), false,
                    "HTTP error calling " + url + ": " + e.getMessage());
        }
    }

    public static String extractErrorDetail(int statusCode, String body) {
        String prefix = "HTTP " + statusCode;
        if (body == null || body.isBlank()) {
            return prefix + " (empty response body)";
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                if (obj.has("error")) {
                    JsonElement err = obj.get("error");
                    if (err.isJsonObject()) {
                        JsonObject errObj = err.getAsJsonObject();
                        String msg = getString(errObj, "message");
                        String type = getString(errObj, "type");
                        String status = getString(errObj, "status");
                        String code = !type.isBlank() ? type : status;
                        if (!msg.isBlank()) {
                            return code.isBlank() ? prefix + ": " + msg : prefix + " (" + code + "): " + msg;
                        }
                    } else if (err.isJsonPrimitive()) {
                        return prefix + ": " + err.getAsString();
                    }
                }
                String directMessage = getString(obj, "message");
                if (!directMessage.isBlank()) {
                    return prefix + ": " + directMessage;
                }
            }
        } catch (Exception ignored) {
            // Fall back to raw snippet
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 400) {
            compact = compact.substring(0, 400) + "...";
        }
        return prefix + ": " + compact;
    }

    public static String getString(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(field).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    public static long getLong(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return 0L;
        }
        try {
            return obj.get(field).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
