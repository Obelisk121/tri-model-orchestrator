package io.github.obelisk121.trimodel.provider;

import io.github.obelisk121.trimodel.model.ChatMessage;
import io.github.obelisk121.trimodel.model.ModelProviderType;
import io.github.obelisk121.trimodel.model.ModelRequest;
import io.github.obelisk121.trimodel.model.ModelResponse;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Executes a local AI CLI tool (e.g., claude, codex, gemini) without shell interpolation.
 */
public final class CliModelClient implements ModelClient {
    private final ModelProviderType providerType;
    private final String executable;
    private final String argsTemplate;
    private final String modelLabel;

    public CliModelClient(ModelProviderType providerType, String executable, String argsTemplate, String modelLabel) {
        this.providerType = providerType;
        this.executable = (executable == null || executable.isBlank())
                ? providerType.getDefaultCliExecutable() : executable.trim();
        this.argsTemplate = argsTemplate == null ? providerType.getDefaultCliArgs() : argsTemplate.trim();
        this.modelLabel = (modelLabel == null || modelLabel.isBlank())
                ? "cli:" + this.executable : modelLabel.trim();
    }

    @Override
    public ModelProviderType getProviderType() {
        return providerType;
    }

    @Override
    public String getModelId() {
        return modelLabel;
    }

    @Override
    public ModelResponse generate(ModelRequest request, BooleanSupplier isCancelled) {
        long startNanos = System.nanoTime();
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            return ModelResponse.error(providerType, modelLabel, "Cancelled", 0);
        }

        String combinedPrompt = formatCombinedPrompt(request);
        List<String> command = new ArrayList<>();
        command.add(executable);

        boolean promptInjectedViaArg = false;
        for (String token : tokenizeArgs(argsTemplate)) {
            if (token.contains("{prompt}")) {
                command.add(token.replace("{prompt}", combinedPrompt));
                promptInjectedViaArg = true;
            } else {
                command.add(token);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        enrichPathForGuiApps(pb.environment());

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return ModelResponse.error(providerType, modelLabel,
                    "Failed to launch CLI '" + executable + "': " + e.getMessage()
                            + ". Ensure the CLI is installed or configure its full path in Settings.",
                    elapsedMs(startNanos));
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outThread = startReaderThread(process.getInputStream(), stdout);
        Thread errThread = startReaderThread(process.getErrorStream(), stderr);

        try {
            if (!promptInjectedViaArg) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(combinedPrompt.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                } catch (IOException ignored) {
                    // Subprocess may have closed stdin early
                }
            } else {
                try {
                    process.getOutputStream().close();
                } catch (IOException ignored) {
                }
            }

            long deadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(Math.max(15, request.timeoutSeconds()));
            while (process.isAlive()) {
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    return ModelResponse.error(providerType, modelLabel, "Cancelled", elapsedMs(startNanos));
                }
                if (System.nanoTime() > deadlineNanos) {
                    process.destroyForcibly();
                    return ModelResponse.error(providerType, modelLabel,
                            "CLI '" + executable + "' timed out after " + request.timeoutSeconds() + "s",
                            elapsedMs(startNanos));
                }
                process.waitFor(120, TimeUnit.MILLISECONDS);
            }

            outThread.join(2000);
            errThread.join(2000);

            int exitCode = process.exitValue();
            String outputText = stdout.toString().trim();
            String errText = stderr.toString().trim();

            if (exitCode != 0) {
                String detail = !errText.isBlank() ? errText : outputText;
                if (detail.length() > 500) {
                    detail = detail.substring(0, 500) + "...";
                }
                return ModelResponse.error(providerType, modelLabel,
                        "CLI '" + executable + "' exited with code " + exitCode + (detail.isBlank() ? "" : ": " + detail),
                        elapsedMs(startNanos));
            }
            if (outputText.isBlank()) {
                return ModelResponse.error(providerType, modelLabel,
                        "CLI '" + executable + "' succeeded but produced no output." + (errText.isBlank() ? "" : " Stderr: " + errText),
                        elapsedMs(startNanos));
            }

            return ModelResponse.ok(providerType, modelLabel, outputText, "", 0, 0, elapsedMs(startNanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ModelResponse.error(providerType, modelLabel, "Interrupted", elapsedMs(startNanos));
        }
    }

    private static String formatCombinedPrompt(ModelRequest request) {
        StringBuilder sb = new StringBuilder();
        if (!request.systemPrompt().isBlank()) {
            sb.append("[SYSTEM INSTRUCTIONS]\n").append(request.systemPrompt().trim()).append("\n\n");
        }
        if (!request.history().isEmpty()) {
            sb.append("[CONVERSATION HISTORY]\n");
            for (ChatMessage msg : request.history()) {
                sb.append(msg.role().toUpperCase()).append(": ").append(msg.content()).append("\n\n");
            }
        }
        sb.append("[TASK]\n").append(request.userPrompt());
        return sb.toString();
    }

    static List<String> tokenizeArgs(String args) {
        List<String> tokens = new ArrayList<>();
        if (args == null || args.isBlank()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean hasToken = false;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '\\' && !inSingle && i + 1 < args.length()) {
                current.append(args.charAt(++i));
                hasToken = true;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                hasToken = true;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                hasToken = true;
            } else if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
            } else {
                current.append(c);
                hasToken = true;
            }
        }
        if (hasToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static Thread startReaderThread(InputStream stream, StringBuilder target) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    target.append(buf, 0, n);
                }
            } catch (IOException ignored) {
            }
        }, "TriModel-CLI-Reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void enrichPathForGuiApps(Map<String, String> env) {
        String sep = File.pathSeparator;
        String home = System.getProperty("user.home", "");
        String current = env.getOrDefault("PATH", "");
        List<String> extras = List.of(
                "/opt/homebrew/bin",
                "/usr/local/bin",
                home + "/.local/bin",
                home + "/.npm-global/bin",
                home + "/.nvm/versions/node/current/bin"
        );
        StringBuilder updated = new StringBuilder(current);
        for (String extra : extras) {
            if (!extra.isBlank() && !current.contains(extra)) {
                if (!updated.isEmpty()) updated.append(sep);
                updated.append(extra);
            }
        }
        env.put("PATH", updated.toString());
    }

    /**
     * Launches the vendor's official CLI login command (e.g., 'claude login', 'codex login')
     * so users can complete browser sign-in through the official CLI directly.
     */
    public static String launchOfficialCliLogin(ModelProviderType type, String executable) {
        String bin = (executable == null || executable.isBlank()) ? type.getDefaultCliExecutable() : executable.trim();
        String loginArgs = type.getDefaultCliLoginArgs();
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.addAll(tokenizeArgs(loginArgs));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        enrichPathForGuiApps(pb.environment());
        try {
            Process p = pb.start();
            // Let the CLI open the default browser; return non-blocking message
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                return "Launched '" + String.join(" ", cmd) + "' — complete sign-in in your browser, then click Test Connection.";
            }
            int code = p.exitValue();
            return code == 0
                    ? "CLI login completed successfully (" + String.join(" ", cmd) + ")."
                    : "CLI login exited with code " + code + ". Try running '" + String.join(" ", cmd) + "' in PyCharm's Terminal.";
        } catch (IOException e) {
            return "Could not launch '" + bin + "': " + e.getMessage() + ". Install the CLI or specify its full path.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "CLI login launch interrupted.";
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
